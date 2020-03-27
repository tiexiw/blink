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
import { Component, OnDestroy, OnInit, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { Subject } from 'rxjs';
import { flatMap, takeUntil } from 'rxjs/operators';
import { JobService } from 'flink-services';

@Component({
  selector       : 'flink-job-overview-drawer-accumulators',
  templateUrl    : './job-overview-drawer-accumulators.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls      : [ './job-overview-drawer-accumulators.component.less' ]
})
export class JobOverviewDrawerAccumulatorsComponent implements OnInit, OnDestroy {
  destroy$ = new Subject();
  listOfAccumulator = [];
  listOfSubTaskAccumulator = [];
  isLoading = true;

  trackAccumulatorBy(index, node) {
    return node.name;
  }

  constructor(private jobService: JobService, private cdr: ChangeDetectorRef) {
  }

  ngOnInit() {
    this.jobService.selectedVertexNode$.pipe(
      takeUntil(this.destroy$),
      flatMap((node) => this.jobService.loadAccumulators(this.jobService.jobDetail.jid, node.id))
    ).subscribe(data => {
      this.isLoading = false;
      this.listOfAccumulator = data.main;
      this.listOfSubTaskAccumulator = data.subtasks || [];
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
