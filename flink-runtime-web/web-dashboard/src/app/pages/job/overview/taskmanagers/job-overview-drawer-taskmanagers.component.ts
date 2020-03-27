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

import { ChangeDetectionStrategy, ChangeDetectorRef, Component, Inject, Input, OnDestroy, OnInit, Optional } from '@angular/core';
import { deepFind } from 'flink-core';
import { NodesItemCorrectInterface } from 'flink-interfaces';
import { JobService, TaskManagerService } from 'flink-services';
import { NzMessageService } from 'ng-zorro-antd';
import { Subject } from 'rxjs';
import { flatMap, takeUntil } from 'rxjs/operators';
import { JOB_OVERVIEW_CONFIG, JobOverviewConfig } from '../job-overview.config';

@Component({
  selector       : 'flink-job-overview-drawer-taskmanagers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl    : './job-overview-drawer-taskmanagers.component.html',
  styleUrls      : [ './job-overview-drawer-taskmanagers.component.less' ]
})
export class JobOverviewDrawerTaskmanagersComponent implements OnInit, OnDestroy {
  @Input() node: NodesItemCorrectInterface;
  listOfTaskManager = [];
  destroy$ = new Subject();
  sortName = null;
  sortValue = null;
  isLoading = true;

  trackTaskManagerBy(index, node) {
    return node.host;
  }

  sort(sort: { key: string, value: string }) {
    this.sortName = sort.key;
    this.sortValue = sort.value;
    this.search();
  }

  search() {
    if (this.sortName) {
      this.listOfTaskManager = [ ...this.listOfTaskManager.sort(
        (pre, next) => {
          if (this.sortValue === 'ascend') {
            return (deepFind(pre, this.sortName) > deepFind(next, this.sortName) ? 1 : -1);
          } else {
            return (deepFind(next, this.sortName) > deepFind(pre, this.sortName) ? 1 : -1);
          }
        }) ];
    }
  }

  getJMX(id) {
    this.taskManagerService.getJMX(id).subscribe();
  }

  getSubTaskLog(task) {
    return this.config.taskManagersLogRouterGetter(task);
  }

  constructor(
    private cdr: ChangeDetectorRef,
    private jobService: JobService,
    private taskManagerService: TaskManagerService,
    private nzMessageService: NzMessageService,
    @Optional() @Inject(JOB_OVERVIEW_CONFIG) private config: JobOverviewConfig) {
  }

  ngOnInit() {
    this.jobService.selectedVertexNode$.pipe(
      takeUntil(this.destroy$),
      flatMap((node) => this.jobService.loadTaskManagers(this.jobService.jobDetail.jid, node.id))
    ).subscribe(data => {
      this.listOfTaskManager = data.taskmanagers;
      this.isLoading = false;
      this.search();
      this.cdr.markForCheck();
    }, () => {
      this.isLoading = false;
      this.cdr.markForCheck();
    });
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }
}
