[![Apache Sling](https://sling.apache.org/res/logos/sling.png)](https://sling.apache.org)

&#32;[![Build Status](https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-feature-modelconverter/job/master/badge/icon)](https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-feature-modelconverter/job/master/)&#32;[![Test Status](https://img.shields.io/jenkins/tests.svg?jobUrl=https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-feature-modelconverter/job/master/)](https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-feature-modelconverter/job/master/test/?width=800&height=600)&#32;[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=apache_sling-org-apache-sling-feature-modelconverter&metric=coverage)](https://sonarcloud.io/dashboard?id=apache_sling-org-apache-sling-feature-modelconverter)&#32;[![Sonarcloud Status](https://sonarcloud.io/api/project_badges/measure?project=apache_sling-org-apache-sling-feature-modelconverter&metric=alert_status)](https://sonarcloud.io/dashboard?id=apache_sling-org-apache-sling-feature-modelconverter)&#32;[![JavaDoc](https://www.javadoc.io/badge/org.apache.sling/org.apache.sling.feature.modelconverter.svg)](https://www.javadoc.io/doc/org.apache.sling/org-apache-sling-feature-modelconverter)&#32;[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.apache.sling/org.apache.sling.feature.modelconverter/badge.svg)](https://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.apache.sling%22%20a%3A%22org.apache.sling.feature.modelconverter%22)&#32;[![feature](https://sling.apache.org/badges/group-feature.svg)](https://github.com/apache/sling-aggregator/blob/master/docs/group/feature.md) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

# Feature Model Converter

The Model Converter can convert between the Sling Provisioning Model and the Sling Feature Model and back.

For further documentation see: https://github.com/apache/sling-org-apache-sling-feature/blob/master/readme.md

## Building

This tool can be built using `mvn clean install`. For testing purposes these
arguments can be provided:
* **test.prov.files.tempdir**: test output folder that must exist before the build
* **test.prov.no.delete**: if no output folder is provided this flag indicates
that the files inside the temporary test output folder is not deleted

## The CLI Tool

**Attention**: as of now the CLI tool is only supporting conversions from
Provisioning Model to Feature Model.

The tool is distributed with a commodity package containing all is needed
in order to convert Provisioning Models into Feature Model files. It will
launch the `ProvisioningToFeature` form the shell:

```
$ unzip -l target/org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT.zip 
Archive:  target/org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT.zip
  Length      Date    Time    Name
---------  ---------- -----   ----
        0  05-23-2019 08:36   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/
        0  05-23-2019 08:36   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/bin/
        0  05-23-2019 08:36   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/lib/
     5998  05-23-2019 06:47   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/README.md
    26081  03-09-2019 13:08   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/lib/geronimo-json_1.0_spec-1.0-alpha-1.jar
    12783  05-23-2019 08:36   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/LICENSE
    32266  05-23-2019 08:36   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/lib/org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT.jar
      178  05-23-2019 06:47   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/NOTICE
   242435  04-04-2019 14:00   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/lib/picocli-3.6.0.jar
     3802  05-23-2019 08:36   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/bin/pm2fm
     3257  05-23-2019 08:36   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/bin/pm2fm.bat
    14769  03-29-2019 06:30   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/lib/org.osgi.annotation.versioning-1.0.0.jar
    41203  03-09-2019 13:15   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/lib/slf4j-api-1.7.25.jar
    15257  03-29-2019 06:30   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/lib/slf4j-simple-1.7.25.jar
   115234  05-14-2019 11:38   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/lib/org.apache.sling.feature-1.0.2.jar
   164176  05-14-2019 11:39   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/lib/org.apache.sling.feature.io-1.0.2.jar
    76683  04-03-2019 09:41   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/lib/org.apache.sling.provisioning.model-1.8.2.jar
   106841  03-29-2019 06:30   org.apache.sling.feature.modelconverter-1.0.3-SNAPSHOT/lib/org.apache.sling.commons.johnzon-1.0.0.jar
---------                     -------
   860963                     18 files
```

once the package is decompressed, open the shell and type:

```
$ ./bin/pm2fm -h
Usage: pm2fm [-DhV] [-g=<groupId>] -i=<provisioningModelsInputDirectory>
             [-n=<name>] -o=<featureModelsOutputDirectory> [-v=<version>]
             [-a=<addFrameworkProperties>]... [-d=<dropVariables>]...
             [-e=<excludeBundles>]... [-r=<runModes>]...
Apache Sling Provisioning Model to Sling Feature Model converter
  -a, --addFrameworkProperty=<addFrameworkProperties>
                             Adds Framework Property to Feature Models. Format:
                               <Model Name>:<Property Name>=<value> (repeat for more)
  -d, --dropVariable=<dropVariables>
                             Variable (by name) in a Feature Model to be excluded
                               (repeat for more)
  -D, --noProvisioningModelName
                             If flagged then the Provisioning Model Name is not added
  -e, --excludeBundle=<excludeBundles>
                             Bundle and/or Bundle Configuration to be excluded
                               (repeat for more)
  -g, --group-id=<groupId>   Overwriting the Group Id of the Model ID
  -h, --help                 Display the usage message
  -i, --provisining-input-directory=<provisioningModelsInputDirectory>
                             The input directory where the Provisioning File are
  -n, --name=<name>          Sets a General Name for all converted Models. This also
                               means that the name is placed in the classifier
  -o, --features-output-directory=<featureModelsOutputDirectory>
                             The output directory where the Feature File will be
                               generated in
  -r, --runMode=<runModes>   Runmode to add to this build (all no-runmodes are
                               included by default, repeat for more)
  -v, --version=<version>    Overwriting the Version of the Model ID
  -V, --useProvidedVersion   If flagged then the provided version will override any
                               given version from Provisioning Model
Copyright(c) 2019 The Apache Software Foundation.```

to see all the available options; a sample execution could look like:

```
$sh ./bin/pm2fm \
    -i $SLING_DEV_HOME/sling-org-apache-sling-starter/src/main/provisioning \
    -o fm.out \
    -g "\${project.groupId}" \
    -v "\${project.version}" \
    -V \
    -n "\${project.artifactId}" \
    -D \
    -a "launchpad:felix.systempackages.substitution=true" \
    -a "launchpad:felix.systempackages.calculate.uses=true" \
    -r ":standalone" \
    -r "oak_tar"
```

**Attention**: the current Model Converter is adding runmode bundles and
configurations with a **id** that has a suffix of **.runmode.<runmode name>**
which will not work with the Feature Launcher as the Feature Model does not
support runtime selection / configuration.
The **-r** argument provides the ability to select the desired runmodes which
then does not add the suffix to the id and drop all other runmodes. See
SLING-8479 for more on this.

**Note**: this will generate all the Feature Models for the current Sling
Provisioning (Sling (PM) Starter). The groupId, artifactId and version is
set to a placeholder and with it the model name is placed inside the classifier.
In addition the Provisioning Model Name is dropped (duplicates) and two
framework properties are added to the launchpad FM to make bundles that uses
Java dependencies activate.

Argument Files for Long Command Lines:

```
# argfile to convert Sling Provisioning Model to Feature Model

# SLING_DEV_HOME points to the sling development folder
-i $SLING_DEV_HOME/sling-org-apache-sling-starter/src/main/provisioning
# Will be created if it does not exists
-o fm.out
# Set the Model Id parts and add the name as classifier 
-g "\${project.groupId}"
-v "\${project.version}"
-n "\${project.artifactId}"
# Override any existing version number
-V
# Delete Provisioning Model Name to avoid duplicates
-D
# Add Launchpad Framework Properties to make bundles with Java dependencies activate
-a "launchpad:felix.systempackages.substitution=true"
-a "launchpad:felix.systempackages.calculate.uses=true"
# Exclude Lauchpad Installer (provided by the feature launcher) and Repository
# Initializer as this is embedded in the configuration file
-e "org.apache.sling.launchpad.installer"
-e "org.apache.sling.jcr.repoinit.impl.RepositoryInitializer"
# This is a build for the standalone OAK Segment Node Store which needs
# these two runmodes but we dropped all others like :webapp or oak_mongo
-r "oak_tar"
-r ":standalone"
```

then execute the command

```
$ ./bin/pm2fm @arfile
````
