# Allure jUnit Listener for FitNesse

[Allure reporting framework](http://allure.qatools.ru/) integration with [FitNesse](http://fitnesse.org)-setup based on 
[hsac-fitnesse-fixtures](https://github.com/fhoeben/hsac-fitnesse-fixtures).

This project contains a jUnit listener which can be added to a test run executing FitNesse tests, which will allow the results
of the test run to be incorporated into an Allure report.
Sometimes an Allure report can add that little bit extra to test results (dashboard with graphs, colors and other 
management-pleasing functionality) and allow you to combine results of multiple test runs, possibly
using different testing frameworks.

The idea is to add this listener to a test run executed by a build/CI server (as described at 
https://github.com/fhoeben/hsac-fitnesse-fixtures#to-run-the-tests-on-a-build-server), so that the test results can then
be incorporated into an Allure report.

## Capturing FitNesse Results

To enable this listener in a project using the 'standard HSAC maven setup': 
* create a dependency to this project (with scope `test`) and 
* set the maven property `extraFailsafeListeners` to `nl.hsac.fitnesse.junit.allure.JUnitAllureFrameworkListener`.

The listener creates data for Allure reporting in `target/allure-results`, to get an actual report you still need to
generate one based on these results. 

## Generating Allure Report

An example `pom.xml` generating the results files during 'integration-tests' and then creating a report in `target/allure-report`
during Maven's 'site' phase is provided below.

In short: mini-manual/featurelist:

* The dashboard shows an aggregate of all runs in target/allure-results (so running multiple suites and generating one 
report is possible - don't clean between runs!)
* If tags are provided on the test pages, these are visible as functional 'stories' in the report
* HSAC's fitnesse report is copied and integrated as an attachment per test case
* If exceptions were thrown, screenshot and pagesource are attached separately

To view the report in a browser, access `target/allure-report` using a webserver (browsers won't allow XHR to file:// urls). 
Fitnesse results are copied in, so you can drill down to technical results inside the dashboard.

### Sample pom.xml Profile
The 'profile' element below provides a sample on how to incorporate both capturing tests results for Allure, and generating
an Allure report in a project based on hsac-fitness-project. It is intended to be incorporated inside the 'profiles'
element in a pom.xml similar to the [one in the hsac-fitnesse sample project](https://github.com/fhoeben/sample-fitnesse-project/blob/master/pom.xml).

It can then be activated by adding a ` -Pallure` to Maven commands, e.g. 
```mvn clean test-compile failsafe:integration-test -DfitnesseSuiteToRun=HsacExamples.SlimTests -Pallure``` or
```mvn site -Pallure```.

```
		<profile>
			<id>allure</id>
			<properties>
				<allure.report.directory>${project.build.directory}/allure-report</allure.report.directory>
				<extraFailsafeListeners>,nl.hsac.fitnesse.junit.allure.JUnitAllureFrameworkListener</extraFailsafeListeners>
			</properties>
                        <dependencies>
                            <dependency>
                                <groupId>nl.hsac</groupId>
                                <artifactId>allure-fitnesse-listener</artifactId>
                                <version>1.0-SNAPSHOT</version>
                                <scope>test</scope>
                            </dependency>
                        </dependencies>
			<build>
				<plugins>
					<plugin>
						<artifactId>maven-resources-plugin</artifactId>
						<version>2.7</version>
						<executions>
							<execution>
								<id>copy-resources</id>
								<phase>site</phase>
								<goals>
									<goal>copy-resources</goal>
								</goals>
								<configuration>
									<outputDirectory>${allure.report.directory}/fitnesseResults</outputDirectory>
									<resources>
										<resource>
											<directory>${project.build.directory}/fitnesse-results</directory>
											<filtering>true</filtering>
										</resource>
									</resources>
								</configuration>
							</execution>
						</executions>
						<dependencies>
							<dependency>
								<groupId>org.apache.maven.shared</groupId>
								<artifactId>maven-filtering</artifactId>
								<version>1.3</version>
							</dependency>
						</dependencies>
					</plugin>
				</plugins>
			</build>
			<reporting>
				<excludeDefaults>true</excludeDefaults>
				<plugins>
					<plugin>
						<groupId>ru.yandex.qatools.allure</groupId>
						<artifactId>allure-maven-plugin</artifactId>
						<version>2.5</version>
						<configuration>
							<resultsDirectory>allure-results</resultsDirectory>
							<reportDirectory>${allure.report.directory}</reportDirectory>
						</configuration>
					</plugin>
				</plugins>
			</reporting>
		</profile>
```