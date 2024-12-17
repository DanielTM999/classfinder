package dtm.discovery.core;

import java.lang.annotation.Annotation;

public interface ClassFinderConfigurations {
    
    ClassFinderErrorHandler defaltHandler = (e) -> {};

    default boolean getAllElements(){
        return true;
    }

    default boolean getAnonimousClass(){
        return false;
    }

    default ClassFinderErrorHandler getHandler(){
        return defaltHandler;
    }

    default Class<? extends Annotation> getFilterByAnnotation(){
        return null;
    }
}
