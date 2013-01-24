### Release notes

#### 23rd Jan 2013

Add [dashboard](docs/riffraff/dashboards) functionality for quickly seeing the last build deployed in different environments.

#### 22nd Jan 2013

Riff-Raff can now pin builds in TeamCity automatically once the build has completed.  New properties under `teamcity`
are used to configure this.

#### 18th Jan 2013

Added API endpoints to Riff-Raff, initially for searching the history but the groundwork has now been laid to
add more endpoints easily.

Documentation available at [docs/riffraff/api](docs/riffraff/api)

#### 17th Jan 2013

Deploys can now be interrupted.  Obviously this is useful when a long running deploy is accidentally started, or
a deploy is causing unintended side effects.

Please be aware that doing this may well leave the target systems in an inconsistent state.

Once the button to stop the deploy has been clicked the deploy will stop at the next opportune moment.  This will either
happen after the current task has finished or, in the case of tasks that poll in a loop, after the next loop has
finished.

If you are not viewing the log on the same server as it was started on then you will instead see a button that will
 take you to the log on the running server.

#### 9th Jan 2013

The preview system has been completely overhauled, bringing the magenta concepts of recipes to the surface and making
it easier to understand how the resolver has come up with the task list.

The resolver has also been changed to ensure that recipes which have per-host tasks are omitted if no hosts are found.

#### 3rd Jan 2013

Resolution of tasks has been changed so that recipes included by multiple recipes are not run multiple times.

#### 2nd Jan 2013

Automatic log scrolling has finally been added.  Your browser will now scroll to the bottom of the log when new data is
added.  Scrolling far enough up from the bottom will disable this behaviour, scrolling back down will enable it again.

#### 31st Dec 2012

Various changes to make it easier to develop on a local machine.  By default there is no external dependency on TeamCity
or the deployinfo executable.  Deployinfo can now be configured to fetch from any URL or executable.

#### 19th Dec 2012

Added pagination to history pages

#### 18th Dec 2012

Added filtering and searching to history pages

#### 6th Dec 2012

Completely re-wrote database mapping layer to decouple the internal data model from that serialised to the database.

This will make it much easier to develop with going forward and also makes searching the history table significantly
faster as less data is transferred from mongo.