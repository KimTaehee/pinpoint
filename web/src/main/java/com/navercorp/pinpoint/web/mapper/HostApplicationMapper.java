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

package com.navercorp.pinpoint.web.mapper;

import java.util.Arrays;

import com.navercorp.pinpoint.common.service.ServiceTypeRegistryService;
import com.navercorp.pinpoint.common.trace.ServiceType;

import com.navercorp.pinpoint.web.service.ApplicationFactory;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.hadoop.hbase.RowMapper;
import org.springframework.stereotype.Component;

import com.navercorp.pinpoint.common.hbase.HBaseTables;
import com.navercorp.pinpoint.web.vo.Application;

/**
 * 
 * @author netspider
 * 
 */
@Component
public class HostApplicationMapper implements RowMapper<Application> {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ApplicationFactory applicationFactory;

    @Override
    public Application mapRow(Result result, int rowNum) throws Exception {
        if (result.isEmpty()) {
            return null;
        }
        byte[] value = result.value();

        if (value.length != HBaseTables.APPLICATION_NAME_MAX_LEN + 2) {
            logger.warn("Invalid value. {}", Arrays.toString(value));
        }

        String applicationName = Bytes.toString(value, 0, HBaseTables.APPLICATION_NAME_MAX_LEN - 1).trim();
        short serviceTypeCode = Bytes.toShort(value, HBaseTables.APPLICATION_NAME_MAX_LEN);
        return this.applicationFactory.createApplication(applicationName, serviceTypeCode);
    }
}
