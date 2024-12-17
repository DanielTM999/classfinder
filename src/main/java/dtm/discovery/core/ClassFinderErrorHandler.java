package dtm.discovery.core;

@FunctionalInterface
public interface ClassFinderErrorHandler {
    void onScanError(Throwable throwable);
}
