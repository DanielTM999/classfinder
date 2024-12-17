package dtm.discovery.finder;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import dtm.discovery.core.ClassFinder;
import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.core.ClassFinderErrorHandler;

public class ClassFinderService implements ClassFinder{

    private ClassFinderErrorHandler handlers;
    private Class<? extends Annotation> annotationFilter;

    @Override
    public Set<Class<?>> find() {
        return find(new ClassFinderConfigurations(){});
    }

    @Override
    public Set<Class<?>> find(ClassFinderConfigurations configurations) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        Class<?> callingClass = getClass();
        for (StackTraceElement stackTraceElement : stackTrace) {
            if(stackTraceElement.getMethodName().equalsIgnoreCase("main")){
                String callingClassName = stackTraceElement.getClassName();
                try {
                    callingClass = Class.forName(callingClassName);
                    find(callingClass);
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

    private Set<Class<?>> encontrarClassesNoPacote(String pacote, ClassFinderConfigurations configurations) {
        this.handlers = configurations.getHandler();
        this.annotationFilter = configurations.getFilterByAnnotation();
        boolean classPathFind = false;

        if(annotationFilter != null && !annotationFilter.isAnnotation()){
            this.annotationFilter = null;
        }

        Set<Class<?>> classes = new HashSet<>();
        String path = pacote.replace('.', '/');
        
        try {
            Enumeration<URL> resources = getClass().getClassLoader().getResources(path);
            while (resources.hasMoreElements()){
                URL resource = resources.nextElement();
                if(resource.getProtocol().equalsIgnoreCase("file")){
                    File directory = new File(resource.getFile());
                    if (directory.exists() && directory.isDirectory()) {
                        classPathFind = true;
                        encontrarClassesNoDiretorio(directory, pacote, classes, configurations);
                    }
                }else if (resource.getProtocol().equals("jar")){
                    String jarFilePath = resource.getFile().substring("file:/".length(), (resource.getFile().indexOf("!")));
                    try (JarFile jar = new JarFile(jarFilePath)){
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()){
                            JarEntry entry = entries.nextElement();
                            String entryName = entry.getName();
    
                            if ((entryName.startsWith(path) || configurations.getAllElements()) && entryName.endsWith(".class")) {
                                String className = entryName.replace('/', '.').replace(".class", "");
                                try {
                                    if (configurations.getAnonimousClass()){
                                        injectToClassList(Class.forName(className), classes);
                                    }else{
                                        if(!entryName.contains("$")){
                                            injectToClassList(Class.forName(className), classes);
                                        }
                                    }
                                }catch (ClassNotFoundException | NoClassDefFoundError e) {
                                    executeHandler(e);
                                } catch (Exception e) {
                                    executeHandler(e);
                                }
                            }
    
                            if (entryName.endsWith(".jar")){
                                String jarInternalPath = "jar:file:" + resource.getFile().replace("\\", "/") + "!/" + entryName;
                                URL jarUrl;
                                try {
                                    String decodedPath = URLDecoder.decode(jarInternalPath, "UTF-8");
                                    jarUrl = new URL(decodedPath);
                                } catch (UnsupportedEncodingException e) {
                                    jarUrl = new URL(jarInternalPath);
                                }
                                
                                classes.addAll(encontrarClassesNoPacoteDentroDoJar(jarUrl, pacote, configurations));
                            }
                        }
                    }catch(Exception e){
                        executeHandler(e);
                    }
                }
            }
            
            if(classPathFind){
                String classpath = System.getProperty("java.class.path");
                List<String> jarPaths = Arrays.stream(classpath.split(";"))
                    .filter(pathJar -> pathJar.endsWith(".jar"))
                .collect(Collectors.toList());
    
                classes.addAll(encontrarClassesNoClassPath(jarPaths, pacote, configurations));
            }

        } catch (Exception e) {
            executeHandler(e);
        }

        return classes;
    }

    private void encontrarClassesNoDiretorio(File directory, String pacote, Set<Class<?>> classes, ClassFinderConfigurations configurations) {
        File[] files = directory.listFiles();
        if(files == null){files = new File[0];}

        for (File file : files){
            if(file.isDirectory()){
                encontrarClassesNoDiretorio(file, pacote + "." + file.getName(), classes, configurations);
            }else if(file.getName().endsWith(".class")){
                String className = pacote + "." + file.getName().replace(".class", "");
                try {

                    if (configurations.getAnonimousClass()){
                        injectToClassList(Class.forName(className), classes);
                    }else{
                        if(!className.contains("$")){
                            injectToClassList(Class.forName(className), classes);
                        }
                    }
                } catch (Exception e) {
                    executeHandler(e);
                }
            }
        }
       
    }

    private Set<Class<?>> encontrarClassesNoPacoteDentroDoJar(URL jarUrl, String pacote, final ClassFinderConfigurations configurations){
        Set<Class<?>> classes = new HashSet<>();

        try (JarFile jarFile = new JarFile(jarUrl.getFile())){
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()){
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if ((entryName.startsWith(pacote.replace('.', '/')) || configurations.getAllElements()) && entryName.endsWith(".class")) {
                    String className = entryName.replace('/', '.').replace(".class", "");

                    try {
                        if (configurations.getAnonimousClass()){
                            injectToClassList(Class.forName(className), classes);
                        }else{
                            if(!entryName.contains("$")){
                                injectToClassList(Class.forName(className), classes);
                            }
                        }
                    }catch (ClassNotFoundException | NoClassDefFoundError e) {
                        executeHandler(e);
                    } catch (Exception e) {
                        executeHandler(e);
                    }
                }

                if (entryName.endsWith(".jar")) {
                    
                    String jarInternalPath = "jar:file:" + jarUrl.getFile().replace("\\", "/") + "!/" + entryName;
                    URL jarUrlInternal;
                    try {
                        String decodedPath = URLDecoder.decode(jarInternalPath, "UTF-8");
                        jarUrlInternal = new URL(decodedPath);
                    } catch (UnsupportedEncodingException e) {
                        jarUrlInternal = new URL(jarInternalPath);
                    }

                    classes.addAll(encontrarClassesNoPacoteDentroDoJar(jarUrlInternal, pacote, configurations));
                }
            }
        }catch (Exception e) {
            executeHandler(e);
        }

        return classes;
    }

    private Set<Class<?>> encontrarClassesNoClassPath(List<String> jarPahts, String pacote, final ClassFinderConfigurations configurations){
        Set<Class<?>> classes = new HashSet<>();
        for (String path : jarPahts) {
            try {
                File file = new File(path);
                URL url = file.toURI().toURL();

                classes.addAll(encontrarClassesNoPacoteDentroDoJar(url, pacote, configurations));
            } catch (Exception e) {
                executeHandler(e);
            }
        }

        return classes;
    }

    private void executeHandler(Throwable th){
        if(this.handlers != null){
            this.handlers.onScanError(th);
        }
    }

    private void injectToClassList(Class<?> clazz, Set<Class<?>> classes){
        if(this.annotationFilter != null){
            if(clazz.isAnnotationPresent(this.annotationFilter)){
                classes.add(clazz);
            }
        }else{
            classes.add(clazz);
        }
    }

}
