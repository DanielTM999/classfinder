package dtm.discovery.core;

import java.util.function.Consumer;

public interface Processor {

    void execute() throws Exception;
    void onError(Consumer<Throwable> action);

}
