package sefirah.communication.sms

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import sefirah.domain.util.DeviceBackgroundWork
import sefirah.common.util.smsPermissionGranted
import sefirah.communication.sms.SMSHelper.MessageLooper.Companion.getLooper
import sefirah.communication.sms.SMSHelper.getConversations
import sefirah.communication.sms.SMSHelper.getMessagesInRange
import sefirah.communication.sms.SMSHelper.getNewestMessageTimestamp
import sefirah.communication.sms.SMSHelper.mConversationUri
import sefirah.communication.sms.SmsMmsUtils.sendMessage
import sefirah.communication.sms.SmsMmsUtils.toHelperSmsAddress
import sefirah.communication.sms.SmsMmsUtils.toHelperSmsAttachment
import sefirah.communication.sms.SmsMmsUtils.toTextMessage
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.model.ConversationInfo
import sefirah.domain.model.ConversationInfoType
import sefirah.domain.model.DevicePreferences
import sefirah.domain.model.TextMessage
import sefirah.domain.model.ThreadRequest
import sefirah.BoundFeature
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsFeature @Inject constructor(
    private val context: Context,
    deviceManager: DeviceManager,
    private val networkManager: NetworkManager,
) : BoundFeature(deviceManager) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialSync = DeviceBackgroundWork(scope)
    private var messageObserver: ContentObserver = MessageContentObserver(Handler(getLooper()!!))

    // Cache of existing thread IDs
    private var existingThreadIds: Set<Long> = emptySet()

    override fun isPrefEnabled(prefs: DevicePreferences) = prefs.messageSync

    override fun hasPermissions(): Boolean = smsPermissionGranted(context)

    override suspend fun onStart() {
        startContentObserver()
    }

    override suspend fun onStop() {
        stopContentObserver()
    }

    override suspend fun onStart(deviceId: String) {
        // A content-provider query can take minutes after resume. Never hold the feature/session
        // lifecycle lock while enumerating SMS: DeviceInfo would block the socket reader behind it.
        initialSync.start(deviceId) {
            try {
                val startedAt = SystemClock.elapsedRealtime()
                Log.d(TAG, "Initial conversation sync started for $deviceId")
                sendAllConversations(deviceId)
                Log.d(TAG, "Initial conversation sync finished in ${SystemClock.elapsedRealtime() - startedAt}ms for $deviceId")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Initial conversation sync failed for $deviceId", error)
            }
        }
    }

    override suspend fun onStop(deviceId: String) {
        initialSync.cancel(deviceId)
    }

    fun stop() {
        initialSync.cancelAll()
        stopContentObserver()
    }

    private var isObserverRegistered = false

    private fun startContentObserver() {
        if (!smsPermissionGranted(context)) return
        Log.d(TAG, "Message content observer started")
        context.contentResolver.registerContentObserver(mConversationUri, true, messageObserver)
        isObserverRegistered = true

        mostRecentTimestampLock.lock()
        mostRecentTimestamp = getNewestMessageTimestamp(context)
        mostRecentTimestampLock.unlock()
        existingThreadIds = emptySet()
    }

    private fun stopContentObserver() {
        if (!isObserverRegistered) return
        context.contentResolver.unregisterContentObserver(messageObserver)
        isObserverRegistered = false
        // Reset state
        mostRecentTimestampLock.lock()
        mostRecentTimestamp = 0
        mostRecentTimestampLock.unlock()
        existingThreadIds = emptySet()
        haveMessagesBeenRequested = false
    }

    /**
     * Keep track of the most-recently-seen message so that we can query for later ones as they arrive
     */
    private var mostRecentTimestamp: Long = 0

    // Since the mostRecentTimestamp is accessed both from the feature's thread and the ContentObserver
    // thread, make sure that access is coherent
    private val mostRecentTimestampLock: Lock = ReentrantLock()

    /**
     * Keep track of whether we have received any packet which requested messages.
     *
     * If not, we will not send updates, since probably the user doesn't care.
     */
    private var haveMessagesBeenRequested: Boolean = false

    private inner class MessageContentObserver
    /**
     * Create a ContentObserver to watch the Messages database. onChange is called for
     * every subscribed change
     *
     * @param handler Handler object used to make the callback
     */
        (handler: Handler?) : ContentObserver(handler) {
        /**
         * The onChange method is called whenever the subscribed-to database changes
         *
         * In this case, this onChange expects to be called whenever *anything* in the Messages
         * database changes and simply reports those updated messages to anyone who might be listening
         */
        override fun onChange(selfChange: Boolean) {
            sendLatestMessage()
            checkForDeletedThreads()
        }
    }

    private fun sendLatestMessage() {
        // Lock so no one uses the mostRecentTimestamp between the moment we read it and the
        // moment we update it. This is because reading the Messages DB can take long.
        mostRecentTimestampLock.lock()

        val messages: List<SMSHelper.Message> = getMessagesInRange(context, null, mostRecentTimestamp, null, false)
        var newMostRecentTimestamp: Long = mostRecentTimestamp
        for (message: SMSHelper.Message? in messages) {
            if (message == null || message.date >= newMostRecentTimestamp) {
                newMostRecentTimestamp = message!!.date
            }
        }

        // Update the most recent counter
        mostRecentTimestamp = newMostRecentTimestamp
        mostRecentTimestampLock.unlock()

        if (messages.isEmpty()) {
            return
        }

        // Group messages by thread ID since they can belong to different threads
        val messagesByThread = messages.groupBy { it.threadID.threadID }
        
        // Send a separate conversation for each thread
        messagesByThread.forEach { (threadId, messagesInThread) ->
            val textMessages = messagesInThread.map {
                it.toTextMessage()
            }

            val isNew = !existingThreadIds.contains(threadId)

            val conversation = ConversationInfo(
                infoType = if (isNew) ConversationInfoType.New else ConversationInfoType.ActiveUpdated,
                threadId = threadId,
                messages = textMessages
            )
            existingThreadIds = existingThreadIds.plus(threadId)
            sendToDesktop(conversation, enabledDevices)
        }
    }

    /**
     * Send all conversations (one message per thread)
     */
    private suspend fun sendAllConversations(deviceId: String) {
        currentCoroutineContext().ensureActive()
        val targetDeviceIds = setOf(deviceId)

        val conversations = getConversations(this.context)
        currentCoroutineContext().ensureActive()
        // Build a set of thread IDs while we process the conversations
        val currentThreadIds = mutableSetOf<Long>()
        
        // For each conversation (already one message per thread from getConversations)
        conversations.forEach { conversationInfo ->
            currentCoroutineContext().ensureActive()
            if (!deviceManager.pairedDevices.value.any { it.deviceId == deviceId && it.connectionState.isConnected }) return
            val threadId = conversationInfo.message.threadID.threadID
            currentThreadIds.add(threadId)
            
            val textMessage = conversationInfo.message.toTextMessage()

            // Create a ConversationInfo with just this one message
            val conversation = ConversationInfo(
                infoType = ConversationInfoType.Active,
                threadId = threadId,
                recipients = conversationInfo.recipients,
                messages = listOf(textMessage)
            )
            sendToDesktop(conversation, targetDeviceIds)
            yield()
        }
        
        // Store the current thread IDs for later comparison
        existingThreadIds = currentThreadIds
    }

    /**
     * Handle a request for all messages in a specific thread
     */
    fun handleThreadRequest(request: ThreadRequest) {
        haveMessagesBeenRequested = true

        val threadID = SMSHelper.ThreadID(request.threadId)

        // Get all messages in the thread
        val messages = if (request.rangeStartTimestamp < 0) {
            SMSHelper.getMessagesInThread(context, threadID,
                if (request.numberToRequest < 0) null else request.numberToRequest)
        } else {
            getMessagesInRange(context, threadID, request.rangeStartTimestamp,
                if (request.numberToRequest < 0) null else request.numberToRequest, true)
        }

        // Convert all messages to TextMessage objects
        val textMessages = messages.map { it.toTextMessage() }

        val conversation = ConversationInfo(
            infoType = ConversationInfoType.Active,
            threadId = request.threadId,
            messages = textMessages
        )
        
        // Send the conversation packet
        sendToDesktop(conversation, enabledDevices)
    }

    private fun sendToDesktop(conversation: ConversationInfo, targetDeviceIds: Set<String> = enabledDevices) {
        targetDeviceIds.forEach { deviceId ->
            networkManager.sendMessage(deviceId, conversation)
        }
    }

    fun sendTextMessage(message: TextMessage) {
        val addresses = message.addresses.toHelperSmsAddress(context)
        val attachments = message.attachments?.toHelperSmsAttachment()
        sendMessage(
            context,
            message.body,
            attachments,
            addresses,
            subID = message.subscriptionId,
        )
    }

    /**
     * Check if any threads were deleted
     */
    private fun checkForDeletedThreads() {
        // Get current threads
        val currentThreadIds = mutableSetOf<Long>()
        getConversations(context).forEach { conversationInfo ->
            currentThreadIds.add(conversationInfo.message.threadID.threadID)
        }

        // Find deleted threads
        val deletedThreadIds = existingThreadIds.minus(currentThreadIds)
        
        // Notify about deleted threads
        if (deletedThreadIds.isNotEmpty()) {
            for (threadId in deletedThreadIds) {
                Log.d(TAG, "Thread deleted: $threadId")
                notifyThreadDeleted(threadId)
            }
        }
        
        // Update our cache for next time
        existingThreadIds = currentThreadIds
    }
    
    /**
     * Notify that a thread was deleted
     */
    private fun notifyThreadDeleted(threadId: Long) {
        val deleteNotification = ConversationInfo(
            infoType = ConversationInfoType.Removed,
            threadId = threadId,
        )
        sendToDesktop(deleteNotification, enabledDevices)
    }

    companion object {
        const val TAG = "SmsFeature"
    }
}
