/*
 * Copyright 2014 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.bootstrap.interceptor.group;

import com.navercorp.pinpoint.bootstrap.interceptor.AfterInterceptor1;
import com.navercorp.pinpoint.bootstrap.interceptor.AroundInterceptor1;
import com.navercorp.pinpoint.bootstrap.interceptor.BeforeInterceptor1;
import com.navercorp.pinpoint.bootstrap.logging.PLogger;
import com.navercorp.pinpoint.bootstrap.logging.PLoggerFactory;

/**
 * @author emeroad
 */
public class GroupedInterceptor1 implements AroundInterceptor1 {
    private final PLogger logger = PLoggerFactory.getLogger(getClass());
    private final boolean debugEnabled = logger.isDebugEnabled();

    private final BeforeInterceptor1 before;
    private final AfterInterceptor1 after;
    private final InterceptorGroup group;
    private final ExecutionPolicy policy;
    
    public GroupedInterceptor1(BeforeInterceptor1 before, AfterInterceptor1 after, InterceptorGroup group, ExecutionPolicy policy) {
        this.before = before;
        this.after = after;
        this.group = group;
        this.policy = policy;
    }
    
    @Override
    public void before(Object target, Object arg0) {
        InterceptorGroupInvocation transaction = group.getCurrentInvocation();
        
        if (transaction.tryEnter(policy)) {
            if (before != null) {
                before.before(target, arg0);
            }
        } else {
            if (debugEnabled) {
                logger.debug("tryBefore() returns false: interceptorGroupTransaction: {}, executionPoint: {}. Skip interceptor {}", new Object[] {transaction, policy, before == null ? null : before.getClass()} );
            }
        }
    }

    @Override
    public void after(Object target, Object result, Throwable throwable, Object arg1) {
        InterceptorGroupInvocation transaction = group.getCurrentInvocation();
        
        if (transaction.canLeave(policy)) {
            if (after != null) {
                after.after(target, result, throwable, arg1);
            }
            transaction.leave(policy);
        } else {
            if (debugEnabled) {
                logger.debug("tryAfter() returns false: interceptorGroupTransaction: {}, executionPoint: {}. Skip interceptor {}", new Object[] {transaction, policy, after == null ? null : after.getClass()} );
            }
        }
    }
}
