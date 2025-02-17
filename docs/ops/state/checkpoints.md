---
title: "Checkpoints"
nav-parent_id: ops_state
nav-pos: 7
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->


* toc
{:toc}

## Overview

Checkpoints make state in Flink fault tolerant by allowing state and the
corresponding stream positions to be recovered, thereby giving the application
the same semantics as a failure-free execution.

See [Checkpointing]({{ site.baseurl }}/dev/stream/state/checkpointing.html) for how to enable and
configure checkpoints for your program.

## Retained Checkpoints

Checkpoints are by default not retained and are only used to resume a
job from failures. They are deleted when a program is cancelled.
You can, however, configure periodic checkpoints to be retained.
Depending on the configuration these *retained* checkpoints are *not*
automatically cleaned up when the job fails or is canceled.
This way, you will have a checkpoint around to resume from if your job fails.

{% highlight java %}
CheckpointConfig config = env.getCheckpointConfig();
config.enableExternalizedCheckpoints(ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
{% endhighlight %}

The `ExternalizedCheckpointCleanup` mode configures what happens with checkpoints when you cancel the job:

- **`ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION`**: Retain the checkpoint when the job is cancelled. Note that you have to manually clean up the checkpoint state after cancellation in this case.

- **`ExternalizedCheckpointCleanup.DELETE_ON_CANCELLATION`**: Delete the checkpoint when the job is cancelled. The checkpoint state will only be available if the job fails.

### Directory Structure

Similarly to [savepoints](savepoints.html), a checkpoint consists
of a meta data file and, depending on the state backend, some additional data
files. The meta data file and data files are stored in the directory that is
configured via `state.checkpoints.dir` in the configuration files, 
and also can be specified for per job in the code.

#### Configure globally via configuration files

{% highlight yaml %}
state.checkpoints.dir: hdfs:///checkpoints/
{% endhighlight %}

#### Configure for per job when constructing the state backend

{% highlight java %}
env.setStateBackend(new RocksDBStateBackend("hdfs:///checkpoints-data/");
{% endhighlight %}

### Difference to Savepoints

Checkpoints have a few differences from [savepoints](savepoints.html). They
- use a state backend specific (low-level) data format, may be incremental.
- do not support Flink specific features like rescaling.

### Resuming from a retained checkpoint

A job may be resumed from a checkpoint just as from a savepoint
by using the checkpoint's meta data file instead (see the
[savepoint restore guide](../cli.html#restore-a-savepoint)). Note that if the
meta data file is not self-contained, the jobmanager needs to have access to
the data files it refers to (see [Directory Structure](#directory-structure)
above).

{% highlight shell %}
$ bin/flink run -s :checkpointMetaDataPath [:runArgs]
{% endhighlight %}

### Resume latest checkpoint automatically
Previously, if we want to resume from retained checkpoint, we must provide the specific directory, which contains the checkpoint meta file such above., to resume checkpoint:

{% highlight shell %}
$ bin/flink run -s {user-defined-checkpoint-dir}/{job-id}/chk-x
{% endhighlight %}

However, if we just want to resume from the last latest checkpoint, this usage is not so friendly for two reasons below:
1. We must know which is the latest checkpoint. In other words, the specific `chk-x` folder must contain the valid checkpoint meta file, otherwise the Flink job would not submit successfully.
1. We must know previous running job's `job-id`, so that we could resume from the last latest checkpoint. However, `job-id` is not so readable and we need other solution to judge which is the last running job.

We introduce a more friendly way to resume job from checkpoint automatically. First of all, we add new command to resume from latest checkpoint of given job with specific `job-id` directory:
{% highlight shell %}
$ bin/flink run -r {user-defined-checkpoint-dir}/{job-id}
{% endhighlight %}
**`Please pay attention that, if no checkpoint could be found in the given directory, the newly submitted job would run from scratch.`**

We also introduce a new configuraiton named `state.checkpoints.create-subdirs` in `flink-conf.yaml`, which has default value as `true`. If no multi jobs with the same checkpoint path would run simultaneously, which could be ensured through external job-manage system. By means of setting this configuraiton as `false`, the sub-directory of `job-id` would not be created, which would be more friendly for usage:
{% highlight shell %}
bin/flink run -r {user-defined-checkpoint-dir}
{% endhighlight %}

{% top %}
