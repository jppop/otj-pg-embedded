#!/bin/bash -ex
#VERSION=9.5.3-1
VERSION=9.6.0-1

RSRC_DIR=$PWD/target/generated-resources

[ -e $RSRC_DIR/.repacked ] && echo "Already repacked, skipping..." && exit 0

cd `dirname $0`

PACKDIR=$(mktemp -d -t wat.XXXXXX)
LINUX_DIST=dist/postgresql-$VERSION-linux-x64-binaries.tar.gz
OSX_DIST=dist/postgresql-$VERSION-osx-binaries.zip
WINDOWS_DIST=dist/postgresql-$VERSION-win-binaries.zip

mkdir -p dist/ target/generated-resources/
[ -e $LINUX_DIST ] || wget -O $LINUX_DIST "http://get.enterprisedb.com/postgresql/postgresql-$VERSION-linux-x64-binaries.tar.gz"
[ -e $OSX_DIST ] || wget -O $OSX_DIST "http://get.enterprisedb.com/postgresql/postgresql-$VERSION-osx-binaries.zip"
[ -e $WINDOWS_DIST ] || wget -O $WINDOWS_DIST "http://get.enterprisedb.com/postgresql/postgresql-$VERSION-windows-x64-binaries.zip"

tar xzf $LINUX_DIST -C $PACKDIR
pushd $PACKDIR/pgsql
tar cJf $RSRC_DIR/postgresql-Linux-x86_64.txz \
  share/postgresql \
  lib \
  bin/initdb \
  bin/pg_ctl \
  bin/postgres

mvn install:install-file -DgeneratePom=true -Dfile=target/generated-resources/postgresql-Linux-x86_64.txz -DgroupId=org.postgresql.server -DartifactId=postgresql -Dversion=$VERSION -Dpackaging=txz -Dclassifier=Linux-x86_64
popd

rm -fr $PACKDIR && mkdir -p $PACKDIR

unzip -q -d $PACKDIR $OSX_DIST
pushd $PACKDIR/pgsql
tar cJf $RSRC_DIR/postgresql-Darwin-x86_64.txz \
  share/postgresql \
  lib/libiconv.2.dylib \
  lib/libxml2.2.dylib \
  lib/libssl.1.0.0.dylib \
  lib/libcrypto.1.0.0.dylib \
  lib/libuuid.1.1.dylib \
  lib/postgresql/*.so \
  bin/initdb \
  bin/pg_ctl \
  bin/postgres

mvn install:install-file -DgeneratePom=true -Dfile=target/generated-resources/postgresql-Darwin-x86_64.txz -DgroupId=org.postgresql.server -DartifactId=postgresql -Dversion=$VERSION -Dpackaging=txz -Dclassifier=Darwin-x86_64

popd

rm -fr $PACKDIR && mkdir -p $PACKDIR

unzip -q -d $PACKDIR $WINDOWS_DIST
pushd $PACKDIR/pgsql
tar cJf $RSRC_DIR/postgresql-Windows-x86_64.txz \
  share \
  lib/iconv.lib \
  lib/libxml2.lib \
  lib/ssleay32.lib \
  lib/ssleay32MD.lib \
  lib/*.dll \
  bin/initdb.exe \
  bin/pg_ctl.exe \
  bin/postgres.exe \
  bin/*.dll

mvn install:install-file -DgeneratePom=true -Dfile=target/generated-resources/postgresql-Windows-x86_64.txz -DgroupId=org.postgresql.server -DartifactId=postgresql -Dversion=$VERSION -Dpackaging=txz -Dclassifier=Windows-x86_64
popd

rm -rf $PACKDIR
touch $RSRC_DIR/.repacked
