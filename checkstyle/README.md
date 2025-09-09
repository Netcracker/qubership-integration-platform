# Checkstyle

[Checkstyle](https://checkstyle.org/) configuration for Qubership Integration Platform.
It is used to ensure code style consistency among Qubership Integration Platform's libraries and services.

## Usage

### Maven-built Java project
Configuration contains rules for java files. It is designed to be used by [Maven Checkstyle Plugin](https://maven.apache.org/plugins/maven-checkstyle-plugin/) and is being built as an ordinary Maven package.
To enable rule checking, one needs to add the corresponding dependency to Checkstyle plugin configuration like is shown below:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-checkstyle-plugin</artifactId>
    <dependencies>
        <dependency>
            <groupId>com.puppycrawl.tools</groupId>
            <artifactId>checkstyle</artifactId>
            <version>9.3</version>
        </dependency>
        <dependency>
            <groupId>org.qubership</groupId>
            <artifactId>qip-checkstyle</artifactId>
            <version>${qip-checkstyle-revision}</version>
        </dependency>
    </dependencies>
    <configuration>
        <configLocation>checkstyle.xml</configLocation>
        <consoleOutput>true</consoleOutput>
        <violationSeverity>warning</violationSeverity>
        <failOnViolation>true</failOnViolation>
        <linkXRef>false</linkXRef>
        <maxAllowedViolations>0</maxAllowedViolations>
        <excludeGeneratedSources>true</excludeGeneratedSources>
        <includeTestSourceDirectory>true</includeTestSourceDirectory>
    </configuration>
    <executions>
        <execution>
            <phase>compile</phase>
            <goals>
                <goal>check</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

Executing rule checking on compile phase guaranties that the project can't be built without complying with the code style rules.

## Contribution

For the details on contribution, see [Contribution Guide](CONTRIBUTING.md).
For details on reporting of security issues see [Security Reporting Process](SECURITY.md).

## Licensing

This software is licensed under Apache License Version 2.0. License text is located in [LICENSE](LICENSE) file.