#!/usr/bin/env bash

#!/usr/bin/env bash
BIN=`dirname "$0"`
BIN=`cd "$BIN"; pwd`

. ${BIN}/common.sh

rm -f $HADOOP_HOME/etc/hadoop/slaves > /dev/null
while IFS='' read -r line || [[ -n "$line" ]]; do
    # if master as worker, start worker locally
    if [ ${line} == $HOSTNAME ]; then
        sh -c sudo echo ${line} > /etc/hostname
    else
        ssh -n ${USER}@${line} "sh -c 'sudo echo $line > /etc/hostname'"
    fi
    echo ${line} >> $HADOOP_HOME/etc/hadoop/slaves
done < "${SOCIALITE_PREFIX}/conf/slaves"