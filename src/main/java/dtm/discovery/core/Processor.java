package dtm.discovery.core;

import dtm.discovery.stereotips.ClassFinderStereotips;

import java.util.function.Consumer;
import java.util.function.Predicate;

public interface Processor {

    void execute() throws Exception;
    void onError(Consumer<Throwable> action);
    void acept(Predicate<ClassFinderStereotips> acept);

}
