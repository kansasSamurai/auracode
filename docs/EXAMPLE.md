# Example

## Introduction

You can use this file to paste tool ouput for rendering by visual studio:

```mermaid
sequenceDiagram
    participant UserController
    participant UserServiceImpl
    participant UserMapper
    UserController->>UserServiceImpl: findById(Long)
    UserServiceImpl->>UserMapper: selectById(Long)
```

> `java -jar target/sourcelens.jar trace --entry "com.example.mapper.UserMapper#selectById(Long)"`

```mermaid
sequenceDiagram
    participant UserController
    participant UserServiceImpl
    participant UserMapper
    UserServiceImpl->>UserMapper: selectById(Long)
    UserController->>UserServiceImpl: findById(Long)
    UserServiceImpl->>UserMapper: selectById(Long)
    UserController->>UserServiceImpl: createUser(String, String)
    UserController->>UserController: createUser(String, String)
    UserServiceImpl->>UserMapper: selectById(Long)
    UserController->>UserServiceImpl: updateEmail(Long, String)
    UserController->>UserController: updateEmail(Long, String)
```

```mermaid
sequenceDiagram
    participant UserController
    participant UserServiceImpl
    participant UserMapper
    UserController->>UserServiceImpl: findById(Long)
    UserServiceImpl->>UserMapper: selectById(Long)
```

## UserController: createUser(String, String)

```mermaid
sequenceDiagram
    participant UserController
    participant UserServiceImpl
    participant UserMapper
    UserController->>UserServiceImpl: createUser(String, String)
    UserServiceImpl->>UserMapper: selectById(Long)
```

## UserController: updateEmail(Long, String)

```mermaid
sequenceDiagram
    participant UserController
    participant UserServiceImpl
    participant UserMapper
    UserController->>UserServiceImpl: updateEmail(Long, String)
    UserServiceImpl->>UserMapper: selectById(Long)
```

## Feature 2.6

## 2.6 - UserController: createUser(String, String)

```mermaid
sequenceDiagram
    participant UserController
    participant UserServiceImpl
    participant UserMapper
    UserController->>UserServiceImpl: createUser(String, String)
    UserServiceImpl->>UserMapper: selectById(Long)
    UserMapper-->>UserServiceImpl: User
    UserServiceImpl-->>UserController: User
```

## 2.6 - UserController: getUser(Long)

```mermaid
sequenceDiagram
    participant UserController
    participant UserServiceImpl
    participant UserMapper
    UserController->>UserServiceImpl: findById(Long)
    UserServiceImpl->>UserMapper: selectById(Long)
    UserMapper-->>UserServiceImpl: User
    UserServiceImpl-->>UserController: User
```

## 2.6 - UserController: updateEmail(Long, String)

```mermaid
sequenceDiagram
    participant UserController
    participant UserServiceImpl
    participant UserMapper
    UserController->>UserServiceImpl: updateEmail(Long, String)
    UserServiceImpl->>UserMapper: selectById(Long)
    UserMapper-->>UserServiceImpl: User
    UserServiceImpl-->>UserController: User
```
