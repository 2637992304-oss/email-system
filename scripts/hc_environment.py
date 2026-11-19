"""Collect versions/resources only, excluding environment variables and credentials."""
import json,subprocess,platform
from hc_verify import ROOT,COMPOSE,sql
out={'host_os':platform.platform(),'python':platform.python_version(),'database':sql('SELECT VERSION();')}
for key,command in {
 'docker_version':['docker','version','--format','{{.Server.Version}}'],
 'docker_resources':['docker','info','--format','CPU={{.NCPU}} Memory={{.MemTotal}}'],
 'container_limits':['docker','inspect','--format','{{.Name}} Running={{.State.Running}} CPU={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}','email-hc-backend-1','email-hc-backend-b-1','email-hc-db-1'],
 'nginx_version':COMPOSE+['exec','-T','frontend','nginx','-v'],
 'redis_version':COMPOSE+['exec','-T','redis','redis-server','--version'],
 'rabbitmq_version':COMPOSE+['exec','-T','rabbitmq','rabbitmqctl','version'],
 'java_version':COMPOSE+['exec','-T','backend','java','-version'],
 'k6_version':['docker','run','--rm','grafana/k6:0.54.0','version'],
}.items():
 result=subprocess.run(command,text=True,encoding='utf-8',capture_output=True);out[key]=(result.stdout+result.stderr).strip()
(ROOT/'docs/evidence/environment.json').write_text(json.dumps(out,indent=2),encoding='utf-8');print(json.dumps(out,indent=2))
