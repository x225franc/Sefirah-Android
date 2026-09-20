package sefirah.worker;

interface IWorkerLauncherService {
    /** Shizuku UserService destroy; AIDL code is 16777114. */
    void destroy() = 16777114;

    void startWorker() = 1;

    /** Runs `pm grant <this package> <permission>` as the Shizuku user (shell). */
    boolean grantPermission(String permission) = 2;
}
