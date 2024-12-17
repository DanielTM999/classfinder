# ClassFinderService

O **ClassFinderService** é uma implementação da interface `ClassFinder` que permite encontrar classes em pacotes especificados de uma aplicação Java. Ele é projetado para ser configurável e fornece suporte à busca em diretórios, arquivos JAR e dentro de arquivos JAR embutidos. Além disso, é possível filtrar classes com base em anotações.

## Funcionalidades Principais

- **Busca em Pacotes**: Encontra classes em pacotes especificados.
- **Suporte a JARs**: Busca classes dentro de arquivos JAR.
- **Filtragem por Anotações**: Permite filtrar classes que possuem uma anotação específica.
- **Configurações de Busca**: Personalize a busca com configurações como inclusão de classes anônimas e busca em todos os elementos de um pacote.
- **Tratamento de Erros**: Fornece um manipulador de erros configurável para lidar com exceções durante a busca.

## Métodos Principais

### `Set<Class<?>> find()`

Inicia a busca usando as configurações padrão.

### `Set<Class<?>> find(ClassFinderConfigurations configurations)`

Realiza a busca com configurações personalizadas.

### `Set<Class<?>> find(Class<?> mainClass)`

Encontra todas as classes dentro do pacote da classe especificada.

### `Set<Class<?>> find(Package mainPackage)`

Busca todas as classes em um pacote.

### `Set<Class<?>> find(String packageName)`

Permite encontrar classes em um pacote usando seu nome como string.

### `Set<Class<?>> find(String packageName, ClassFinderConfigurations configurations)`

Realiza a busca em um pacote com configurações personalizadas.

## Configurações de Busca

As configurações são definidas através da interface `ClassFinderConfigurations`, que possui os seguintes atributos principais:

- **getAnonimousClass()**: Determina se classes anônimas devem ser incluídas.
- **getAllElements()**: Indica se todos os elementos de um pacote devem ser considerados ou se deve ser aplicado um filtro de pacote.
- **getHandler()**: Retorna o manipulador de erros.
- **getFilterByAnnotation()**: Define a anotação para filtrar as classes.

## Funcionamento Interno

### Busca em Diretórios

O método `encontrarClassesNoDiretorio` percorre recursivamente os arquivos de um diretório, adicionando à lista de classes todas aquelas que correspondem ao pacote especificado.

### Busca em JARs

O método `encontrarClassesNoPacoteDentroDoJar` processa arquivos JAR, identificando classes que correspondam ao pacote ou configurando para incluir todos os elementos.

### Busca no ClassPath

O método `encontrarClassesNoClassPath` escaneia arquivos JAR no classpath da aplicação para encontrar classes relevantes.

## Tratamento de Erros

Erros encontrados durante a busca são tratados pelo método `executeHandler`, que utiliza a implementação de `ClassFinderErrorHandler` definida nas configurações.

## Exemplo de Uso

```java
ClassFinderService finder = new ClassFinderService();
Set<Class<?>> classes = finder.find("com.example.pacote");

for (Class<?> clazz : classes) {
    System.out.println(clazz.getName());
}
```

Com configurações personalizadas:

```java
ClassFinderConfigurations configurations = new ClassFinderConfigurations() {
    @Override
    public boolean getAnonimousClass() {
        return false;
    }

    @Override
    public ClassFinderErrorHandler getHandler() {
        return throwable -> System.err.println("Erro ao buscar classe: " + throwable.getMessage());
    }

    @Override
    public Class<? extends Annotation> getFilterByAnnotation() {
        return MinhaAnotacao.class;
    }
};

Set<Class<?>> classes = finder.find("com.example.pacote", configurations);
```

## Dependências

- Certifique-se de que os pacotes e arquivos JAR estejam acessíveis no classpath da aplicação.

## Limitações

- Classes que lançam `NoClassDefFoundError` ou `ClassNotFoundException` durante o carregamento serão ignoradas.
- Não suporta pacotes que não estão no classpath ou arquivos JAR corrompidos.

O **ClassFinderService** é uma ferramenta poderosa para automatizar a descoberta de classes em aplicações Java, permitindo maior flexibilidade e escalabilidade.

