# MyBatis Sample — Test Fixture

This directory contains a minimal, realistic Spring Boot + MyBatis layered application
used as a static test fixture for the SourceLens `index` command.

**No build system is included** — the indexer only needs source text.

## Known call chain

```
UserController#getUser(Long)
  → UserServiceImpl#findById(Long)
    → UserMapper#selectById(Long)
```

## Structure

```
src/com/example/
├── controller/UserController.java   REST controller
├── service/
│   ├── UserService.java             interface
│   └── UserServiceImpl.java         implementation
├── mapper/UserMapper.java           MyBatis mapper interface
└── model/User.java                  POJO
resources/mapper/UserMapper.xml      MyBatis SQL map (parsed in Phase 3.1, not 1.1)
```
