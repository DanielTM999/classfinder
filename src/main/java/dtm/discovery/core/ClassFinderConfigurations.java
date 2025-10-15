package dtm.discovery.core;

import dtm.discovery.stereotips.ClassFinderStereotips;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public interface ClassFinderConfigurations {
    
    ClassFinderErrorHandler defaltHandler = (e) -> {};

    default boolean getAllElements(){
        return true;
    }

    default boolean getAnonimousClass(){
        return true;
    }

    default ClassFinderErrorHandler getErrorHandler(){
        return defaltHandler;
    }
    default Predicate<ClassFinderStereotips> getAceptHandler(){
        return (e) -> true;
    }

    default Class<? extends Annotation> getFilterByAnnotation(){
        return null;
    }

    default boolean ignoreSubJars(){return true;}

    default boolean ignoreMainJar(){return true;}

    default List<String> getIgnorePackges(){
        return new ArrayList<>(List.of("sun", "com.sun", "jdk.internal", "lombok"));
    }

    default List<String> getIgnoreJarsTerms(){
        return new ArrayList<>();
    }
}
