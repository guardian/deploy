Riff-Raff
=========

"Deploy the transit beam"

About
-----

The Guardian's scala-based deployment system is designed to automate deploys by providing a web application that 
performs and records deploys, as well as providing various integration points for automating deployment pipelines.

Requirements
-----

Riff-Raff and Magenta have been built with the tools we use at the Guardian
and you will find it easiest if you use a similar set of tools. Riff-Raff:

 - relies on artifacts and `riff-raff.yaml` files describing builds being in S3 buckets with the artifacts having paths of 
  the form `project-name/build-number`
 - uses the AWS SDK and [Prism](https://github.com/guardian/prism) to do resource discovery
 - stores configuration, history and logs in a PostgreSQL database and a handful of DynamoDB tables (the eventual aim is to ditch DynamoDB altogether)

Documentation
-----

The documentation is available in the application (under the Documentation menu) but can also be viewed under 
[riff-raff/public/docs](riff-raff/public/docs) in GitHub.

In action
-----

Screenshots don't do a lot to show how Riff-Raff works in practice - but here are
a handful anyway, just to give a hint.

***

![Deploy history](contrib/img/deployment_history.png)
The deploy history view - this shows all deploys that have ever been done (in this case filtered on PROD and projects containing 'mobile')

***

![Deploy log](contrib/img/deployment_view.png)
This is what a single deploy looks like - displaying the overall result and the list of tasks that were executed.

***

![Request a deploy](contrib/img/deployment_request.png)
The simple form for requesting a deploy can be seen here (further options are available after previewing)

***

![Continuous deployment configuration](contrib/img/deployment_continuous.png)
Riff-Raff polls our build server frequently and can be configured to automatically start a deploy for newly completed builds

How do I run Riff-Raff locally if I want to hack on it?
-------------------------------------------------------

Assuming you have Java 11 or later installed, 

 * Create a basic configuration file at ~/.gu/riff-raff.conf replacing placeholders with appropriate values
```
artifact.aws.bucketName=<ARTIFACTS BUCKET NAME>
build.aws.bucketName=<BUILDS BUCKET NAME>

db.default.url="jdbc:postgresql://localhost:7432/riffraff"
db.default.user="riffraff"
db.default.hostname="riffraff"
db.default.password="riffraff"

lookup.prismUrl=<PRISM URL>
lookup.source="prism"
```
 * Run `./script/start` from the project root (add `--debug` to attach a remote debugger on port 9999)
 * Visit http://localhost:9000/
 * Details of how to configure Riff-Raff can then be found at http://localhost:9000/docs/riffraff/administration/properties 


What is still left to do?
------

See the `TODO.txt` file in this project
