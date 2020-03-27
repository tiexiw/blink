/*
 *   Licensed to the Apache Software Foundation (ASF) under one
 *   or more contributor license agreements.  See the NOTICE file
 *   distributed with this work for additional information
 *   regarding copyright ownership.  The ASF licenses this file
 *   to you under the Apache License, Version 2.0 (the
 *   "License"); you may not use this file except in compliance
 *   with the License.  You may obtain a copy of the License at
 *       http://www.apache.org/licenses/LICENSE-2.0
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

import { Component, Input, OnInit } from '@angular/core';
import { TaskManagerService } from 'flink-services';

@Component({
  selector   : 'flink-task-manager-status',
  templateUrl: './task-manager-status.component.html',
  styleUrls  : [ './task-manager-status.component.less' ]
})
export class TaskManagerStatusComponent implements OnInit {
  @Input() isLoading = true;
  listOfNavigation = [
    { pathOrParam: 'resource', title: 'Allocated Resource' },
    { pathOrParam: 'metrics', title: 'Metrics' },
    { pathOrParam: 'log', title: 'Log' }
  ];

  get detail() {
    return this.taskManagerService.taskManagerDetail;
  }

  constructor(private taskManagerService: TaskManagerService) {
  }

  ngOnInit() {
  }

}
