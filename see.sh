ssh gengl@master "kill -9 $(ps aux|grep '[s]ocialite.async.Entry'|awk '{print $2}')"
ssh gengl@hadoop0 "kill -9 $(ps aux|grep '[s]ocialite.async.Entry'|awk '{print $2}')"
ssh gengl@hadoop1 "kill -9 $(ps aux|grep '[s]ocialite.async.Entry'|awk '{print $2}')"
ssh gengl@hadoop2 "kill -9 $(ps aux|grep '[s]ocialite.async.Entry'|awk '{print $2}')"
ssh gengl@hadoop3 "kill -9 $(ps aux|grep '[s]ocialite.async.Entry'|awk '{print $2}')"