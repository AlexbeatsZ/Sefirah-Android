package sefirah.privileged;

interface IPrivilegedBridge {
    void destroy() = 16777114;
    String readClipboardText() = 1;
    String getBluetoothCatalog() = 2;
    String executeBluetoothCommand(String action, String deviceKey, boolean enabled) = 3;
    int startSftpServer(String username, String password, String rootPath) = 4;
    void stopSftpServer() = 5;
}
