package in.ilovemyfiles.common;

public class StorageQuotaException extends AppException {
    public StorageQuotaException(String message) {
        super(message, 400);
    }
}
