package dtm.discovery.finder;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

import dtm.discovery.core.ClassFinder;
import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.core.ClassFinderErrorHandler;
import dtm.discovery.core.Processor;

public class ClassFinderService implements ClassFinder {
    private ClassFinderErrorHandler handlers;
    private final Set<Class<?>> classesLoaded;

    public ClassFinderService() {
        this.classesLoaded = ConcurrentHashMap.newKeySet();
    }

    public ClassFinderService(ClassFinderErrorHandler handlers) {
        this.handlers = handlers;
        this.classesLoaded = ConcurrentHashMap.newKeySet();
    }

    @Override
    public Set<Class<?>> find() {
        return find(new ClassFinderConfigurations() {});
    }

    @Override
    public Set<Class<?>> find(ClassFinderConfigurations configurations) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        Class<?> callingClass = getClass();
        for (StackTraceElement stackTraceElement : stackTrace) {
            if (stackTraceElement.getMethodName().equalsIgnoreCase("main")) {
                String callingClassName = stackTraceElement.getClassName();
                try {
                    callingClass = Class.forName(callingClassName);
                } catch (ClassNotFoundException e) {
                    executeHandler(e);
                }
                break;
            }
        }
        return find(callingClass, configurations);
    }

    @Override
    public Set<Class<?>> find(Class<?> mainClass) {
        Package packageMain = mainClass.getPackage();
        return find(packageMain);
    }

    @Override
    public Set<Class<?>> find(Package mainPackage) {
        return find(mainPackage.getName());
    }

    @Override
    public Set<Class<?>> find(String packageName) {
        return find(packageName, new ClassFinderConfigurations() {});
    }

    @Override
    public Set<Class<?>> find(Class<?> mainClass, ClassFinderConfigurations configurations) {
        return find(mainClass.getPackage(), configurations);
    }

    @Override
    public Set<Class<?>> find(Package mainPackage, ClassFinderConfigurations configurations) {
        return find(mainPackage.getName(), configurations);
    }

    @Override
    public Set<Class<?>> find(String packageName, ClassFinderConfigurations configurations) {
        try {
            return encontrarClassesNoPacote(packageName, configurations);
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    @Override
    public Set<Class<?>> loadByDirectory(String path) {
        return loadByDirectory(path, null);
    }

    @Override
    public Set<Class<?>> loadByDirectory(String path, ClassFinderConfigurations configurations) {
        File rootDir = new File(path);
        Set<Class<?>> classes = ConcurrentHashMap.newKeySet();
        try{
            Processor processor = new SimpleDirectoryProcessor(rootDir, classes);

            processor.onError(this::executeHandler);
            processor.execute();

            this.classesLoaded.addAll(classes);
        }catch (Exception e){
            executeHandler(e);
        }
        return classes;
    }

    @Override
    public Set<Class<?>> getLoadedClasses() {
        return this.classesLoaded;
    }

    @Override
    public void close() throws Exception {
        handlers = null;
        this.classesLoaded.clear();
    }

    private Set<Class<?>> encontrarClassesNoPacote(String pacote, ClassFinderConfigurations configurations) {
        final ClassFinderConfigurations configurationsFinal = configureConfigurations(configurations);
        AtomicBoolean atomicBoolean = new AtomicBoolean(false);

        Set<Class<?>> classes = ConcurrentHashMap.newKeySet();
        Set<String> jarProcessed = ConcurrentHashMap.newKeySet();
        String path = pacote.replace('.', '/');

        List<CompletableFuture<?>> tasks = new ArrayList<>();

        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()){
            Enumeration<URL> resourcesEnumeration = getResourcesEnumeration(path);
            while (resourcesEnumeration.hasMoreElements()){
                final URL resource = resourcesEnumeration.nextElement();
                final String protocol = resource.getProtocol();

                tasks.add(CompletableFuture.runAsync(() -> {
                   try{
                       switch (protocol){
                           case "jar" -> {
                               if (jarProcessed.add(resource.getFile())) {
                                    URL jarUrl = getJarByUrl(resource);
                                    Processor processor = new JarProcessor(
                                            jarUrl,
                                            classes,
                                            jarProcessed,
                                            pacote,
                                            configurationsFinal
                                    );
                                    processor.onError(this::executeHandler);
                                    processor.execute();
                               }
                               break;
                           }
                           case "file" -> {
                               atomicBoolean.set(true);
                               File directory = new File(resource.getFile());
                               Processor processor = new DirectoryProcessor(
                                       directory,
                                       pacote,
                                       classes,
                                       configurationsFinal
                               );
                               processor.onError(this::executeHandler);
                               processor.execute();
                               break;
                           }
                           default -> {
                               break;
                           }
                       }
                   }catch (Exception e){
                       throw new RuntimeException(e);
                   }
                }, executorService).exceptionally(ex -> {
                    executeHandler(ex);
                    return null;
                }));
            }
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

            if(atomicBoolean.get()){
                Processor processor = new ClasspathProcessor(classes, jarProcessed, pacote, configurationsFinal);
                processor.onError(this::executeHandler);
                processor.execute();
            }

        } catch (Exception e) {
            executeHandler(e);
        }
        classesLoaded.addAll(classes);
        return classes;
    }

    private ClassFinderConfigurations configureConfigurations(ClassFinderConfigurations configurations){
        if(configurations != null){
            this.handlers = (configurations.getHandler() != null) ? configurations.getHandler() : (e) -> {};
        }else{
            this.handlers = (e) -> {};
        }
        return (configurations != null) ? configurations : new ClassFinderConfigurations() {};
    }

    private void executeHandler(Throwable th) {
        if (this.handlers != null) {
            this.handlers.onScanError(th);
        }
    }

    private Enumeration<URL> getResourcesEnumeration(String path) throws Exception{
        return getResourcesEnumeration(getClass(), path);
    }

    private Enumeration<URL> getResourcesEnumeration(Class<?> clazz, String path) throws Exception {
        ClassLoader classLoader = clazz.getClassLoader();
        return classLoader.getResources(path);
    }

    private URL getJarByUrl(URL resource) throws Exception{
        String path = resource.getPath();
        String jarPath = path.substring(path.indexOf("file:"), path.indexOf("!"));
        return new URI(jarPath).toURL();
    }

}
