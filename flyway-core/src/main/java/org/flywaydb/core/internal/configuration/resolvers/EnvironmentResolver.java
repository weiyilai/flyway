/*-
 * ========================LICENSE_START=================================
 * flyway-core
 * ========================================================================
 * Copyright (C) 2010 - 2026 Red Gate Software Ltd
 * ========================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.flywaydb.core.internal.configuration.resolvers;

import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.ProgressLogger;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.extensibility.ConfigurationExtension;
import org.flywaydb.core.internal.configuration.models.EnvironmentModel;
import org.flywaydb.core.internal.configuration.models.ResolvedEnvironment;

public class EnvironmentResolver {

    private final Map<String, PropertyResolver> propertyResolvers;
    private final Map<String, ? extends EnvironmentProvisioner> environmentProvisioners;

    public EnvironmentResolver(final Map<String, PropertyResolver> propertyResolvers,
        final Map<String, ? extends EnvironmentProvisioner> environmentProvisioners) {
        this.propertyResolvers = new HashMap<>(propertyResolvers);
        this.environmentProvisioners = new HashMap<>(environmentProvisioners);
    }

    public ResolvedEnvironment resolve(final String environmentName,
        final EnvironmentModel environment,
        final Configuration configuration,
        final ProgressLogger progress) {
        return resolve(environmentName, environment, ProvisionerMode.Provision, configuration, progress);
    }

    public ResolvedEnvironment resolve(final String environmentName,
        final EnvironmentModel environment,
        final ProvisionerMode mode,
        final Configuration configuration,
        final ProgressLogger progress) {
        final Map<String, ConfigurationExtension> resolverConfigs;
        if (environment.getResolvers() != null && !environment.getResolvers().isEmpty()) {
            if (!ConfigurationExtension.JACKSON_DATABIND_PRESENT) {
                throw new FlywayException(
                    "Reading resolver configuration requires 'jackson-databind' on the classpath. Add it as a dependency to use this feature.",
                    CoreErrorCode.CONFIGURATION);
            }
            resolverConfigs = new EnvironmentResolverConfigurationMapCreator().createMap(environment,
                configuration.getPluginRegister());
        } else {
            resolverConfigs = null;
        }

        final PropertyResolverContext context = new PropertyResolverContextImpl(environmentName,
            configuration,
            propertyResolvers,
            resolverConfigs);

        final ResolvedEnvironment result = new ResolvedEnvironment();
        result.setConnectRetries(environment.getConnectRetries());
        result.setConnectRetriesInterval(environment.getConnectRetriesInterval());
        result.setInitSql(environment.getInitSql());

        progress.pushSteps(2);
        final ProgressLogger provisionProgress = progress.subTask("provision");
        final ProgressLogger resolveProgress = progress.subTask("resolve");

        final EnvironmentProvisioner provisioner = getProvisioner(environment.getProvisioner(),
            context,
            provisionProgress);
        if (mode == ProvisionerMode.Provision) {
            provisioner.preProvision(context, provisionProgress);
        } else if (mode == ProvisionerMode.Reprovision) {
            provisioner.preReprovision(context, provisionProgress);
        }

        progress.log("Resolving environment properties " + environmentName);
        if (environment.getJdbcProperties() != null) {
            final Map<String, String> jdbcResolvedProps = new HashMap<>();
            for (final Map.Entry<String, String> entry : environment.getJdbcProperties().entrySet()) {
                jdbcResolvedProps.put(entry.getKey(), context.resolveValue(entry.getValue(), resolveProgress));
            }
            result.setJdbcProperties(jdbcResolvedProps);
        }

        result.setPassword(context.resolveValue(environment.getPassword(), resolveProgress));
        result.setUser(context.resolveValue(environment.getUser(), resolveProgress));
        result.setUrl(context.resolveValue(environment.getUrl(), resolveProgress));
        result.setDriver(context.resolveValue(environment.getDriver(), resolveProgress));
        result.setSchemas(environment.getSchemas() != null ? environment.getSchemas()
            .stream()
            .map(s -> context.resolveValue(s, resolveProgress))
            .toList() : null);
        result.setProvisionerMode(mode);

        if (mode == ProvisionerMode.Provision) {
            provisioner.postProvision(context, result, provisionProgress);
        } else if (mode == ProvisionerMode.Reprovision) {
            provisioner.postReprovision(context, result, provisionProgress);
        }

        return result;
    }

    private EnvironmentProvisioner getProvisioner(final String provisionerName,
        final PropertyResolverContext context,
        final ProgressLogger progress) {
        final String name = context.resolveValue(provisionerName, progress);
        if (name != null) {
            if (!environmentProvisioners.containsKey(provisionerName)) {
                throw new FlywayException("Unknown provisioner '"
                    + provisionerName
                    + "' for environment "
                    + context.getEnvironmentName(), CoreErrorCode.CONFIGURATION);
            }
            return environmentProvisioners.get(provisionerName);
        }
        return new EnvironmentProvisionerNone();
    }
}
