package dtm.discovery.core;

import java.util.Set;

public interface ClassFinder extends AutoCloseable {
    Set<Class<?>> find();
    Set<Class<?>> find(ClassFinderConfigurations configurations);
    Set<Class<?>> find(Class<?> mainClass);
    Set<Class<?>> find(Package mainPackage);
    Set<Class<?>> find(String packageName);
    Set<Class<?>> find(Class<?> mainClass, ClassFinderConfigurations configurations);
    Set<Class<?>> find(Package mainPackage, ClassFinderConfigurations configurations);
    Set<Class<?>> find(String packageName, ClassFinderConfigurations configurations);

    Set<Class<?>> loadByDirectory(String path);

    Set<Class<?>> getLoadedClasses();
}
