---
title: "Time Attributes"
nav-parent_id: streaming_tableapi
nav-pos: 2
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

Flink is able to process streaming data based on different notions of *time*.

- *Processing time* refers to the system time of the machine (also known as "wall-clock time") that is executing the respective operation.
- *Event time* refers to the processing of streaming data based on timestamps which are attached to each row. The timestamps can encode when an event happened.
- *Ingestion time* is the time that events enter Flink; internally, it is treated similarly to event time.

For more information about time handling in Flink, see the introduction about [Event Time and Watermarks]({{ site.baseurl }}/dev/event_time.html).

This page explains how time attributes can be defined for time-based operations in Flink's Table API & SQL.

* This will be replaced by the TOC
{:toc}

Introduction to Time Attributes
-------------------------------

Time-based operations such as windows in both the [Table API]({{ site.baseurl }}/dev/table/tableApi.html#group-windows) and [SQL]({{ site.baseurl }}/dev/table/sql.html#group-windows) require information about the notion of time and its origin. Therefore, tables can offer *logical time attributes* for indicating time and accessing corresponding timestamps in table programs.

Time attributes can be part of every table schema. They are defined when creating a table from a `DataStream` or are pre-defined when using a `TableSource`. Once a time attribute has been defined at the beginning, it can be referenced as a field and can used in time-based operations.

As long as a time attribute is not modified and is simply forwarded from one part of the query to another, it remains a valid time attribute. Time attributes behave like regular timestamps and can be accessed for calculations. If a time attribute is used in a calculation, it will be materialized and becomes a regular timestamp. Regular timestamps do not cooperate with Flink's time and watermarking system and thus can not be used for time-based operations anymore.

Table programs require that the corresponding time characteristic has been specified for the streaming environment:

<div class="codetabs" markdown="1">
<div data-lang="java" markdown="1">
{% highlight java %}
final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

env.setStreamTimeCharacteristic(TimeCharacteristic.ProcessingTime); // default

// alternatively:
// env.setStreamTimeCharacteristic(TimeCharacteristic.IngestionTime);
// env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
{% endhighlight %}
</div>
<div data-lang="scala" markdown="1">
{% highlight scala %}
val env = StreamExecutionEnvironment.getExecutionEnvironment

env.setStreamTimeCharacteristic(TimeCharacteristic.ProcessingTime) // default

// alternatively:
// env.setStreamTimeCharacteristic(TimeCharacteristic.IngestionTime)
// env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
{% endhighlight %}
</div>
</div>

Processing Time
---------------

Processing time allows a table program to produce results based on the time of the local machine. It is the simplest notion of time but does not provide determinism. It neither requires timestamp extraction nor watermark generation.

There are two ways to define a processing time attribute.

#### During DataStream-to-Table Conversion

The processing time attribute is defined with the `.proctime` property during schema definition. The time attribute must only extend the physical schema by an additional logical field. Thus, it can only be defined at the end of the schema definition.

<div class="codetabs" markdown="1">
<div data-lang="java" markdown="1">
{% highlight java %}
DataStream<Tuple2<String, String>> stream = ...;

// declare an additional logical field as a processing time attribute
Table table = tEnv.fromDataStream(stream, "Username, Data, UserActionTime.proctime");

WindowedTable windowedTable = table.window(Tumble.over("10.minutes").on("UserActionTime").as("userActionWindow"));
{% endhighlight %}
</div>
<div data-lang="scala" markdown="1">
{% highlight scala %}
val stream: DataStream[(String, String)] = ...

// declare an additional logical field as a processing time attribute
val table = tEnv.fromDataStream(stream, 'UserActionTimestamp, 'Username, 'Data, 'UserActionTime.proctime)

val windowedTable = table.window(Tumble over 10.minutes on 'UserActionTime as 'userActionWindow)
{% endhighlight %}
</div>
</div>

#### Using a TableSource

The processing time attribute is defined by a `TableSource` that implements the `DefinedProctimeAttribute` interface. The logical time attribute is appended to the physical schema defined by the return type of the `TableSource`.

<div class="codetabs" markdown="1">
<div data-lang="java" markdown="1">
{% highlight java %}
// define a table source with a processing attribute
public class UserActionSource implements StreamTableSource<Row>, DefinedProctimeAttribute {

	@Override
	public TypeInformation<Row> getReturnType() {
		String[] names = new String[] {"Username" , "Data"};
		TypeInformation[] types = new TypeInformation[] {Types.STRING(), Types.STRING()};
		return Types.ROW(names, types);
	}

	@Override
	public DataStream<Row> getDataStream(StreamExecutionEnvironment execEnv) {
		// create stream
		DataStream<Row> stream = ...;
		return stream;
	}

	@Override
	public String getProctimeAttribute() {
		// field with this name will be appended as a third field
		return "UserActionTime";
	}
}

// register table source
tEnv.registerTableSource("UserActions", new UserActionSource());

WindowedTable windowedTable = tEnv
	.scan("UserActions")
	.window(Tumble.over("10.minutes").on("UserActionTime").as("userActionWindow"));
{% endhighlight %}
</div>
<div data-lang="scala" markdown="1">
{% highlight scala %}
// define a table source with a processing attribute
class UserActionSource extends StreamTableSource[Row] with DefinedProctimeAttribute {

	override def getReturnType = {
		val names = Array[String]("Username" , "Data")
		val types = Array[TypeInformation[_]](Types.STRING, Types.STRING)
		Types.ROW(names, types)
	}

	override def getDataStream(execEnv: StreamExecutionEnvironment): DataStream[Row] = {
		// create stream
		val stream = ...
		stream
	}

	override def getProctimeAttribute = {
		// field with this name will be appended as a third field
		"UserActionTime"
	}
}

// register table source
tEnv.registerTableSource("UserActions", new UserActionSource)

val windowedTable = tEnv
	.scan("UserActions")
	.window(Tumble over 10.minutes on 'UserActionTime as 'userActionWindow)
{% endhighlight %}
</div>
</div>

Event Time
----------

Event time allows a table program to produce results based on the time that is contained in every record. This allows for consistent results even in case of out-of-order events or late events. It also ensures replayable results of the table program when reading records from persistent storage.

Additionally, event time allows for unified syntax for table programs in both batch and streaming environments. A time attribute in a streaming environment can be a regular field of a record in a batch environment.

In order to handle out-of-order events and distinguish between on-time and late events in streaming, Flink needs to extract timestamps from events and make some kind of progress in time (so-called [watermarks]({{ site.baseurl }}/dev/event_time.html)).

An event time attribute can be defined either during DataStream-to-Table conversion or by using a TableSource.

#### During DataStream-to-Table Conversion

The event time attribute is defined with the `.rowtime` property during schema definition. [Timestamps and watermarks]({{ site.baseurl }}/dev/event_time.html) must have been assigned in the `DataStream` that is converted.

There are two ways of defining the time attribute when converting a `DataStream` into a `Table`. Depending on whether the specified `.rowtime` field name exists in the schema of the `DataStream` or not, the timestamp field is either

- appended as a new field to the schema or
- replaces an existing field.

In either case the event time timestamp field will hold the value of the `DataStream` event time timestamp.

<div class="codetabs" markdown="1">
<div data-lang="java" markdown="1">
{% highlight java %}

// Option 1:

// extract timestamp and assign watermarks based on knowledge of the stream
DataStream<Tuple2<String, String>> stream = inputStream.assignTimestampsAndWatermarks(...);

// declare an additional logical field as an event time attribute
Table table = tEnv.fromDataStream(stream, "Username, Data, UserActionTime.rowtime");


// Option 2:

// extract timestamp from first field, and assign watermarks based on knowledge of the stream
DataStream<Tuple3<Long, String, String>> stream = inputStream.assignTimestampsAndWatermarks(...);

// the first field has been used for timestamp extraction, and is no longer necessary
// replace first field with a logical event time attribute
Table table = tEnv.fromDataStream(stream, "UserActionTime.rowtime, Username, Data");

// Usage:

WindowedTable windowedTable = table.window(Tumble.over("10.minutes").on("UserActionTime").as("userActionWindow"));
{% endhighlight %}
</div>
<div data-lang="scala" markdown="1">
{% highlight scala %}

// Option 1:

// extract timestamp and assign watermarks based on knowledge of the stream
val stream: DataStream[(String, String)] = inputStream.assignTimestampsAndWatermarks(...)

// declare an additional logical field as an event time attribute
val table = tEnv.fromDataStream(stream, 'Username, 'Data, 'UserActionTime.rowtime)


// Option 2:

// extract timestamp from first field, and assign watermarks based on knowledge of the stream
val stream: DataStream[(Long, String, String)] = inputStream.assignTimestampsAndWatermarks(...)

// the first field has been used for timestamp extraction, and is no longer necessary
// replace first field with a logical event time attribute
val table = tEnv.fromDataStream(stream, 'UserActionTime.rowtime, 'Username, 'Data)

// Usage:

val windowedTable = table.window(Tumble over 10.minutes on 'UserActionTime as 'userActionWindow)
{% endhighlight %}
</div>
</div>

#### Using a TableSource

The event time attribute is defined by a `TableSource` that implements the `DefinedRowtimeAttribute` interface. The `getRowtimeAttribute()` method returns the name of an existing field that carries the event time attribute of the table and is of type `LONG` or `TIMESTAMP`.

Moreover, the `DataStream` returned by the `getDataStream()` method must have watermarks assigned that are aligned with the defined time attribute. Please note that the timestamps of the `DataStream` (the ones which are assigned by a `TimestampAssigner`) are ignored. Only the values of the `TableSource`'s rowtime attribute are relevant.

<div class="codetabs" markdown="1">
<div data-lang="java" markdown="1">
{% highlight java %}
// define a table source with a rowtime attribute
public class UserActionSource implements StreamTableSource<Row>, DefinedRowtimeAttribute {

	@Override
	public TypeInformation<Row> getReturnType() {
		String[] names = new String[] {"Username", "Data", "UserActionTime"};
		TypeInformation[] types =
		    new TypeInformation[] {Types.STRING(), Types.STRING(), Types.LONG()};
		return Types.ROW(names, types);
	}

	@Override
	public DataStream<Row> getDataStream(StreamExecutionEnvironment execEnv) {
		// create stream
		// ...
		// assign watermarks based on the "UserActionTime" attribute
		DataStream<Row> stream = inputStream.assignTimestampsAndWatermarks(...);
		return stream;
	}

	@Override
	public String getRowtimeAttribute() {
		// Mark the "UserActionTime" attribute as event-time attribute.
		return "UserActionTime";
	}
}

// register the table source
tEnv.registerTableSource("UserActions", new UserActionSource());

WindowedTable windowedTable = tEnv
	.scan("UserActions")
	.window(Tumble.over("10.minutes").on("UserActionTime").as("userActionWindow"));
{% endhighlight %}
</div>
<div data-lang="scala" markdown="1">
{% highlight scala %}
// define a table source with a rowtime attribute
class UserActionSource extends StreamTableSource[Row] with DefinedRowtimeAttribute {

	override def getReturnType = {
		val names = Array[String]("Username" , "Data", "UserActionTime")
		val types = Array[TypeInformation[_]](Types.STRING, Types.STRING, Types.LONG)
		Types.ROW(names, types)
	}

	override def getDataStream(execEnv: StreamExecutionEnvironment): DataStream[Row] = {
		// create stream
		// ...
		// assign watermarks based on the "UserActionTime" attribute
		val stream = inputStream.assignTimestampsAndWatermarks(...)
		stream
	}

	override def getRowtimeAttribute = {
		// Mark the "UserActionTime" attribute as event-time attribute.
		"UserActionTime"
	}
}

// register the table source
tEnv.registerTableSource("UserActions", new UserActionSource)

val windowedTable = tEnv
	.scan("UserActions")
	.window(Tumble over 10.minutes on 'UserActionTime as 'userActionWindow)
{% endhighlight %}
</div>
</div>

{% top %}
