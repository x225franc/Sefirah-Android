package com.castle.sefirah.di

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.komu.sekia.di.AppCoroutineScope
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import sefirah.Feature
import sefirah.actions.ActionFeature
import sefirah.clipboard.ClipboardFeature
import sefirah.communication.call.CallLogFeature
import sefirah.communication.call.CallStateFeature
import sefirah.communication.sms.SmsFeature
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.interfaces.NotificationCallback
import sefirah.domain.interfaces.PreferencesRepository
import sefirah.domain.interfaces.SocketFactory
import sefirah.data.repository.DeviceManagerImpl
import sefirah.data.repository.PreferencesRepositoryImpl
import sefirah.media.PlaybackFeature
import sefirah.media.RemotePlaybackFeature
import sefirah.network.NetworkManagerImpl
import sefirah.network.SocketFactoryImpl
import sefirah.notification.NotificationFeature
import sefirah.playsound.PlaySoundFeature
import sefirah.screenshot.ScreenshotFeature
import sefirah.status.RemoteDeviceStatusFeature
import sefirah.storage.SftpFeature
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppModule {

    @Binds
    abstract fun bindSocketFactory(impl: SocketFactoryImpl): SocketFactory

    @Binds
    abstract fun bindDeviceManager(impl: DeviceManagerImpl): DeviceManager

    @Binds
    abstract fun bindNetworkManager(impl: NetworkManagerImpl): NetworkManager

    @Binds
    abstract fun bindPreferencesRepository(impl: PreferencesRepositoryImpl): PreferencesRepository

    @Binds
    @IntoSet
    abstract fun bindClipboardFeature(feature: ClipboardFeature): Feature

    @Binds
    @IntoSet
    abstract fun bindNotificationFeature(feature: NotificationFeature): Feature

    @Binds
    @IntoSet
    abstract fun bindPlaybackFeature(feature: PlaybackFeature): Feature

    @Binds
    @IntoSet
    abstract fun bindSmsFeature(feature: SmsFeature): Feature

    @Binds
    @IntoSet
    abstract fun bindCallStateFeature(feature: CallStateFeature): Feature

    @Binds
    @IntoSet
    abstract fun bindCallLogFeature(feature: CallLogFeature): Feature

    @Binds
    @IntoSet
    abstract fun bindSftpFeature(feature: SftpFeature): Feature

    @Binds
    @IntoSet
    abstract fun bindActionFeature(feature: ActionFeature): Feature

    @Binds
    @IntoSet
    abstract fun bindRemotePlaybackFeature(feature: RemotePlaybackFeature): Feature

    @Binds
    @IntoSet
    abstract fun bindRemoteStatusFeature(feature: RemoteDeviceStatusFeature): Feature

    @Binds
    @IntoSet
    abstract fun bindPlaySoundFeature(feature: PlaySoundFeature): Feature

    @Binds
    @IntoSet
    abstract fun bindScreenshotFeature(feature: ScreenshotFeature): Feature

    companion object {
        @Provides
        fun provideAppContext(@ApplicationContext context: Context) = context

        @Provides
        @Singleton
        fun provideAppCoroutineScope(): AppCoroutineScope {
            return object : AppCoroutineScope {
                override val coroutineContext =
                    SupervisorJob() + Dispatchers.Main.immediate + CoroutineName("App")
            }
        }

        @Provides
        @Singleton
        fun provideNotificationCallback(
            notificationFeature: NotificationFeature,
            playbackFeature: PlaybackFeature
        ): NotificationCallback = object : NotificationCallback {
            override fun onNotificationPosted(notification: StatusBarNotification) {
                notificationFeature.onNotificationPosted(notification)
                playbackFeature.onNotificationPosted(notification)
            }

            override fun onNotificationRemoved(notification: StatusBarNotification) {
                notificationFeature.onNotificationRemoved(notification)
                playbackFeature.onNotificationRemoved(notification)
            }

            override fun onListenerConnected(service: NotificationListenerService) {
                notificationFeature.onListenerConnected(service)
                playbackFeature.onListenerConnected(service)
            }

            override fun onListenerDisconnected() {
                notificationFeature.onListenerDisconnected()
                playbackFeature.onListenerDisconnected()
            }
        }
    }
}
