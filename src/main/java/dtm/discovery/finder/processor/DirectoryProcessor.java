package dtm.discovery.finder.processor;

import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.core.Processor;
import dtm.discovery.stereotips.ClassFinderStereotips;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class DirectoryProcessor implements Processor {

    private static final String CLASS_SUFFIX = ".class";
    private static final int PARALLEL_THRESHOLD = 32;

    private final File root;
    private final String packageName;
    private final Set<Class<?>> processedClasses;
    private final ClassFinderConfigurations configurations;
    private Consumer<Throwable> errorAction = e -> {};
    private Predicate<ClassFinderStereotips> acept;

    public DirectoryProcessor(
            File root,
            String packageName,
            Set<Class<?>> processedClasses,
            ClassFinderConfigurations configurations
    ) {
        this.root = root;
        this.packageName = packageName;
        this.processedClasses = processedClasses;
        this.configurations = configurations;
    }

    @Override
    public void execute() throws Exception{
        if (root == null || !root.isDirectory()) return;

        List<String> classNames = new ArrayList<>();
        collect(root, packageName, classNames);
        loadAll(classNames);
    }

    @Override
    public void onError(Consumer<Throwable> action) {
        if(action != null) this.errorAction = action;
    }

    @Override
    public void acept(Predicate<ClassFinderStereotips> acept) {
        this.acept = (acept != null) ? acept : (e) -> true;
    }

    private void collect(File directory, String pacote, List<String> classNames){
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            final boolean isDirectory = file.isDirectory();

            if (!acept.test(stereotipsOf(file, isDirectory))) continue;

            if (isDirectory) {
                collect(file, pacote + "." + file.getName(), classNames);
                continue;
            }

            final String fileName = file.getName();
            if (!fileName.endsWith(CLASS_SUFFIX)) continue;

            String className = pacote + "." + fileName.substring(0, fileName.length() - CLASS_SUFFIX.length());
            if (configurations.getAnonimousClass() || !className.contains("$")) {
                classNames.add(className);
            }
        }
    }

    private void loadAll(List<String> classNames){
        if (classNames.isEmpty()) return;

        final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        if (classNames.size() < PARALLEL_THRESHOLD) {
            for (String className : classNames) {
                load(className, classLoader);
            }
            return;
        }

        try {
            classNames.parallelStream().forEach(className -> load(className, classLoader));
        } catch (Exception e) {
            errorAction.accept(e);
        }
    }

    private void load(String className, ClassLoader classLoader){
        try {
            injectToClassList(Class.forName(className, false, classLoader));
        } catch (Throwable e) {
            errorAction.accept(e);
        }
    }

    private ClassFinderStereotips stereotipsOf(File file, boolean isDirectory){
        return new ClassFinderStereotips() {
            @Override
            public URL getArchiverUrl() {
                try {
                    return file.toURI().toURL();
                } catch (MalformedURLException e) {
                    return null;
                }
            }

            @Override
            public StereotipsProtocols getArchiverProtocol() {
                if (isDirectory) return StereotipsProtocols.DIR;
                return file.getName().endsWith(".jar") ? StereotipsProtocols.JAR : StereotipsProtocols.FILE;
            }
        };
    }

    private void injectToClassList(Class<?> clazz) {
        if (this.configurations.getFilterByAnnotation() != null) {
            if (clazz.isAnnotationPresent(this.configurations.getFilterByAnnotation())) {
                processedClasses.add(clazz);
            }
        } else {
            processedClasses.add(clazz);
        }
    }

}
