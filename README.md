Fork work
=======================================

Forked work from [opentable/otj-pg-embedded](https://github.com/opentable/otj-pg-embedded)

The Open Table project purpose is *to allow developers to unit test with a real PostgreSQL without requiring end users to install and set up a database cluster*.

This project allows you to embed PostgreSQL in a Java application.

The main differences are:
- Postgres is a dependency  
 Declaring Postgres as a dependency allows you a include only the targeted plateform (Windows, Linux or OS X).
- Work with an already extracted Postgres server  
 When you package a Java application (a desktop application in our case), you can choose to unpack Postgres during the installation avoiding to include the postgres as a bundle extracted when users run the application for the first time.