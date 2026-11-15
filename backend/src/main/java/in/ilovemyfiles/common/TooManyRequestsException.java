package in.ilovemyfiles.common;

public class TooManyRequestsException extends AppException {
    public TooManyRequestsException(String message) {
        super(message, 429);
    }
}
