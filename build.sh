#!/bin/sh
# Сборка AnarchyCore.jar из /data/project/AquaAnarchy/source
set -e
P=/data/project/AquaAnarchy; B=/data/build
CP=$(find $B/libraries $B/versions -name '*.jar' | tr '\n' ':')$P/plugins/grimac.jar   # GrimAC API - для моста ИИ-античита
rm -rf $B/out && mkdir -p $B/out
java --add-modules jdk.compiler -cp "$CP" -XX:+UseSerialGC $B/Javac.java -proc:none --release 21 -nowarn -encoding UTF-8 -cp "$CP" -d $B/out $(find $P/source/src -name '*.java')
cp $P/source/res/* $B/out/
cd $B/out && rm -f $B/AnarchyCore.jar && jar_tool() { python3 -c "import shutil;shutil.make_archive('$B/AnarchyCore','zip','.')" && mv $B/AnarchyCore.zip $B/AnarchyCore.jar; }; jar_tool
echo "OK: $B/AnarchyCore.jar"
