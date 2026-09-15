# Notification Management Service

A Spring Boot application built with Java 21 for managing notifications.

## Tech Stack

- Java 21
- Spring Boot 4.1.1
- Spring Web MVC
- Spring Data JPA
- Spring Validation
- H2 in-memory database
- Lombok
- Maven Wrapper

## Project Structure

```text
notification-management-service/
├── .mvn/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/nms/
│   │   └── resources/
│   │       └── application.yaml
│   └── test/
│       └── java/
├── .gitattributes
├── .gitignore
├── mvnw
├── mvnw.cmd
├── pom.xml
├── README.md
└── .vscode/
```

## Prerequisites

- Java 21
- Maven (optional, since the project includes the Maven Wrapper)
- Git

## Setup

### 1. Install Java 21

If you use SDKMAN:

```bash
sdk install java 21.0.12-tem
sdk use java 21.0.12-tem
```

Alternatively, ensure your system `JAVA_HOME` is pointing to a Java 21 JDK.

### 2. Clone and run

```bash
git clone <repository-url>
cd notification-management-service
./mvnw clean install
./mvnw spring-boot:run
```

On Windows:

```powershell
mvnw.cmd clean install
mvnw.cmd spring-boot:run
```

## Application Configuration

The application configuration is defined in:

- `src/main/resources/application.yaml`

Current configuration:

```yaml
spring:
  application:
    name: notification-management-service
```

## Running Tests

```bash
./mvnw test
```

## Build

```bash
./mvnw clean package
```

## GitHub

This repository is initialized as a Git project and includes the standard project metadata files:

- `.gitignore`
- `.gitattributes`
- `mvnw` / `mvnw.cmd`
- `.mvn/`

## Notes

- The project targets Java 21 via the Maven property `java.version` in `pom.xml`.
- The default runtime is configured for Java 21 in the workspace and user VS Code settings for local development.

## License

This project does not specify a license yet.
