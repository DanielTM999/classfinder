package dtm.discovery.finder.simple;

import dtm.discovery.core.ClassFinder;
import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.core.ClassFinderErrorHandler;
import dtm.discovery.core.Processor;
import dtm.discovery.finder.processor.*;
import dtm.discovery.stereotips.ClassFinderStereotips;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

public class ClassFinderProjectService implements ClassFinder {

    private ClassFinderErrorHandler errorHandlers;
    private Predicate<ClassFinderStereotips> scanAcepptHandler;
    private final Set<Class<?>> classesLoaded;

    public ClassFinderProjectService() {
        this.classesLoaded = ConcurrentHashMap.newKeySet();
    }

    public ClassFinderProjectService(ClassFinderErrorHandler errorHandlers) {
        this.errorHandlers = errorHandlers;
        this.classesLoaded = ConcurrentHashMap.newKeySet();
    }

    public ClassFinderProjectService(ClassFinderErrorHandler errorHandlers, Predicate<ClassFinderStereotips> scanAcepptHandler) {
        this.errorHandlers = errorHandlers;
        this.scanAcepptHandler = scanAcepptHandler;
        this.classesLoaded = ConcurrentHashMap.newKeySet();
    }

    public ClassFinderProjectService(Predicate<ClassFinderStereotips> scanAcepptHandler) {
        this.scanAcepptHandler = scanAcepptHandler;
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
                    executeErrorHandler(e);
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
        Map<File, Set<Class<?>>> classesMap = new ConcurrentHashMap<>();
        Set<Class<?>> classesSet = ConcurrentHashMap.newKeySet();
        try{
            configureConfigurations(configurations);
            Processor processor = new SimpleDirectoryProcessor(rootDir, classesMap);

            processor.onError(this::executeErrorHandler);
            processor.acept((configurations != null) ? configurations.getAceptHandler() : null);
            processor.execute();
            classesMap.values().forEach(classesSet::addAll);

            this.classesLoaded.addAll(classesSet);
        }catch (Exception e){
            executeErrorHandler(e);
        }
        return classesSet;
    }

    @Override
    public Map<File, Set<Class<?>>> loadGroupedByDirectory(String path) {
        return loadGroupedByDirectory(path, null);
    }

    @Override
    public Map<File, Set<Class<?>>> loadGroupedByDirectory(String path, ClassFinderConfigurations configurations) {
        File rootDir = new File(path);
        Map<File, Set<Class<?>>> classesMap = new ConcurrentHashMap<>();
        try{
            configureConfigurations(configurations);
            Processor processor = new SimpleDirectoryProcessor(rootDir, classesMap);

            processor.onError(this::executeErrorHandler);
            processor.acept((configurations != null) ? configurations.getAceptHandler() : null);
            processor.execute();

            classesMap.values().forEach(this.classesLoaded::addAll);
        }catch (Exception e){
            executeErrorHandler(e);
        }

        return classesMap;
    }

    @Override
    public Set<Class<?>> getLoadedClasses() {
        return this.classesLoaded;
    }

    @Override
    public void close() throws Exception {
        this.errorHandlers = null;
        this.scanAcepptHandler = null;
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
                                    Processor processor = new FastProjectJarProcessor(
                                            jarUrl,
                                            classes,
                                            pacote,
                                            configurationsFinal
                                    );
                                    processor.onError(this::executeErrorHandler);
                                    processor.acept(scanAcepptHandler);
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
                                processor.onError(this::executeErrorHandler);
                                processor.acept(scanAcepptHandler);
                                processor.execute();
                                break;
                            }
                        }
                    }catch (Exception e){
                        throw new RuntimeException(e);
                    }
                }, executorService).exceptionally(ex -> {
                    executeErrorHandler(ex);
                    return null;
                }));
            }

            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

            if(atomicBoolean.get()){
                Processor processor = new ClasspathProcessor(classes, jarProcessed, pacote, configurationsFinal);
                processor.onError(this::executeErrorHandler);
                processor.execute();
            }
        }catch (Exception e) {
            executeErrorHandler(e);
        }
        classesLoaded.addAll(classes);
        return classes;
    }

    private ClassFinderConfigurations configureConfigurations(ClassFinderConfigurations configurations){
        if(configurations != null){
            this.errorHandlers = (configurations.getErrorHandler() != null) ? configurations.getErrorHandler() : (e) -> {};
            this.scanAcepptHandler = (configurations.getAceptHandler() != null) ? configurations.getAceptHandler() : (e) -> true;
        }else{
            this.errorHandlers = (e) -> {};
            this.scanAcepptHandler = (e) -> true;
        }

        return (configurations != null) ? configurations : new ClassFinderConfigurations() {};
    }

    private void executeErrorHandler(Throwable th) {
        if (this.errorHandlers != null) {
            this.errorHandlers.onScanError(th);
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
