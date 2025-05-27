package dtm.discovery.finder;

import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.core.Processor;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public class ClasspathProcessor implements Processor {

    private final Set<Class<?>> processedClasses;
    private final Set<String> jarProcessed;
    private final ClassFinderConfigurations configurations;
    private final String classpath;
    private final String packageName;

    private Consumer<Throwable> errorAction = e -> {};

    public ClasspathProcessor(
            Set<Class<?>> processedClasses,
            Set<String> jarProcessed,
            String packageName,
            ClassFinderConfigurations configurations
    ) {
        this.processedClasses = processedClasses;
        this.jarProcessed = jarProcessed;
        this.configurations = configurations;
        this.classpath = System.getProperty("java.class.path");
        this.packageName = packageName;
    }

    @Override
    public void execute() throws Exception{
        List<String> jarPaths = Arrays.stream(classpath.split(";"))
                .filter(pathJar -> pathJar.endsWith(".jar"))
                .toList();

        for (String jarPath : jarPaths){
            if(jarProcessed.add(jarPath)){
                if(ignore(jarPath)) continue;
                File file = new File(jarPath);
                URL jarUrl = file.toURI().toURL();
                Processor processor = new JarProcessor(jarUrl, processedClasses, jarProcessed, packageName, configurations);
                processor.onError(errorAction);
                processor.execute();
            }
        }
    }

    @Override
    public void onError(Consumer<Throwable> action) {
        if(action != null) this.errorAction = action;
    }

    private boolean ignore(String jarPath){
        String lowerJarPath = jarPath.toLowerCase();
        return configurations.getIgnoreJarsTerms()
                .stream()
                .map(String::toLowerCase)
                .anyMatch(lowerJarPath::contains);
    }

}
