/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.logsdb;

import org.elasticsearch.cluster.metadata.ComposableIndexTemplate;
import org.elasticsearch.cluster.metadata.ComposableIndexTemplateMetadata;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.DataStreamTestHelper;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.Template;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.IndexSortConfig;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.MapperTestUtils;
import org.elasticsearch.index.mapper.SourceFieldMapper;
import org.elasticsearch.license.License;
import org.elasticsearch.license.LicenseService;
import org.elasticsearch.license.MockLicenseState;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.elasticsearch.common.settings.Settings.builder;
import static org.elasticsearch.xpack.logsdb.SyntheticSourceLicenseServiceTests.createEnterpriseLicense;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LogsdbIndexModeSettingsProviderTests extends ESTestCase {

    private static final String DATA_STREAM_NAME = "logs-app1";
    public static final String DEFAULT_MAPPING = """
        {
            "_doc": {
                "properties": {
                    "@timestamp": {
                        "type": "date"
                    },
                    "message": {
                        "type": "keyword"
                    },
                    "host.name": {
                        "type": "keyword"
                    }
                }
            }
        }
        """;

    private SyntheticSourceLicenseService syntheticSourceLicenseService;
    private final AtomicInteger newMapperServiceCounter = new AtomicInteger();

    @Before
    public void setup() throws Exception {
        MockLicenseState licenseState = MockLicenseState.createMock();
        when(licenseState.isAllowed(any())).thenReturn(true);
        var licenseService = new SyntheticSourceLicenseService(Settings.EMPTY);
        licenseService.setLicenseState(licenseState);
        var mockLicenseService = mock(LicenseService.class);
        License license = createEnterpriseLicense();
        when(mockLicenseService.getLicense()).thenReturn(license);
        syntheticSourceLicenseService = new SyntheticSourceLicenseService(Settings.EMPTY);
        syntheticSourceLicenseService.setLicenseState(licenseState);
        syntheticSourceLicenseService.setLicenseService(mockLicenseService);
    }

    private LogsdbIndexModeSettingsProvider withSyntheticSourceDemotionSupport(boolean enabled) {
        newMapperServiceCounter.set(0);
        var provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", enabled).build()
        );
        provider.init(im -> {
            newMapperServiceCounter.incrementAndGet();
            return MapperTestUtils.newMapperService(xContentRegistry(), createTempDir(), im.getSettings(), im.getIndex().getName());
        }, IndexVersion::current, true);
        return provider;
    }

    private Settings generateLogsdbSettings(Settings settings) throws IOException {
        return generateLogsdbSettings(settings, null);
    }

    private Settings generateLogsdbSettings(Settings settings, String mapping) throws IOException {
        Metadata metadata = Metadata.EMPTY_METADATA;
        var provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", true).build()
        );
        provider.init(im -> {
            newMapperServiceCounter.incrementAndGet();
            return MapperTestUtils.newMapperService(xContentRegistry(), createTempDir(), im.getSettings(), im.getIndex().getName());
        }, IndexVersion::current, true);
        var result = provider.getAdditionalIndexSettings(
            DataStream.getDefaultBackingIndexName(DATA_STREAM_NAME, 0),
            DATA_STREAM_NAME,
            IndexMode.LOGSDB,
            metadata,
            Instant.now(),
            settings,
            mapping == null ? List.of() : List.of(new CompressedXContent(mapping))
        );
        return builder().put(result).build();
    }

    public void testDisabled() throws IOException {
        final LogsdbIndexModeSettingsProvider provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", false).build()
        );

        final Settings additionalIndexSettings = provider.getAdditionalIndexSettings(
            null,
            "logs-apache-production",
            null,
            Metadata.EMPTY_METADATA,
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.EMPTY,
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertTrue(additionalIndexSettings.isEmpty());
    }

    public void testOnIndexCreation() throws IOException {
        final LogsdbIndexModeSettingsProvider provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", true).build()
        );

        final Settings additionalIndexSettings = provider.getAdditionalIndexSettings(
            "logs-apache-production",
            null,
            null,
            Metadata.EMPTY_METADATA,
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.EMPTY,
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertTrue(additionalIndexSettings.isEmpty());
    }

    public void testOnExplicitStandardIndex() throws IOException {
        final LogsdbIndexModeSettingsProvider provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", true).build()
        );

        final Settings additionalIndexSettings = provider.getAdditionalIndexSettings(
            null,
            "logs-apache-production",
            null,
            Metadata.EMPTY_METADATA,
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.builder().put(IndexSettings.MODE.getKey(), IndexMode.STANDARD.getName()).build(),
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertTrue(additionalIndexSettings.isEmpty());
    }

    public void testOnExplicitTimeSeriesIndex() throws IOException {
        final LogsdbIndexModeSettingsProvider provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", true).build()
        );

        final Settings additionalIndexSettings = provider.getAdditionalIndexSettings(
            null,
            "logs-apache-production",
            null,
            Metadata.EMPTY_METADATA,
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.builder().put(IndexSettings.MODE.getKey(), IndexMode.TIME_SERIES.getName()).build(),
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertTrue(additionalIndexSettings.isEmpty());
    }

    public void testNonLogsDataStream() throws IOException {
        final LogsdbIndexModeSettingsProvider provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", true).build()
        );

        final Settings additionalIndexSettings = provider.getAdditionalIndexSettings(
            null,
            "logs",
            null,
            Metadata.EMPTY_METADATA,
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.EMPTY,
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertTrue(additionalIndexSettings.isEmpty());
    }

    public void testWithoutLogsComponentTemplate() throws IOException {
        final LogsdbIndexModeSettingsProvider provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", true).build()
        );

        final Settings additionalIndexSettings = provider.getAdditionalIndexSettings(
            null,
            "logs-apache-production",
            null,
            buildMetadata(List.of("*"), List.of()),
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.EMPTY,
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertIndexMode(additionalIndexSettings, IndexMode.LOGSDB.getName());
    }

    public void testWithLogsComponentTemplate() throws IOException {
        final LogsdbIndexModeSettingsProvider provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", true).build()
        );

        final Settings additionalIndexSettings = provider.getAdditionalIndexSettings(
            null,
            "logs-apache-production",
            null,
            buildMetadata(List.of("*"), List.of("logs@settings")),
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.EMPTY,
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertIndexMode(additionalIndexSettings, IndexMode.LOGSDB.getName());
    }

    public void testWithMultipleComponentTemplates() throws IOException {
        final LogsdbIndexModeSettingsProvider provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", true).build()
        );

        final Settings additionalIndexSettings = provider.getAdditionalIndexSettings(
            null,
            "logs-apache-production",
            null,
            buildMetadata(List.of("*"), List.of("logs@settings", "logs@custom")),
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.EMPTY,
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertIndexMode(additionalIndexSettings, IndexMode.LOGSDB.getName());
    }

    public void testWithCustomComponentTemplatesOnly() throws IOException {
        final LogsdbIndexModeSettingsProvider provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", true).build()
        );

        final Settings additionalIndexSettings = provider.getAdditionalIndexSettings(
            null,
            "logs-apache-production",
            null,
            buildMetadata(List.of("*"), List.of("logs@custom", "custom-component-template")),
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.EMPTY,
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertIndexMode(additionalIndexSettings, IndexMode.LOGSDB.getName());
    }

    public void testNonMatchingTemplateIndexPattern() throws IOException {
        final LogsdbIndexModeSettingsProvider provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", true).build()
        );

        final Settings additionalIndexSettings = provider.getAdditionalIndexSettings(
            null,
            "logs-apache-production",
            null,
            buildMetadata(List.of("standard-apache-production"), List.of("logs@settings")),
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.EMPTY,
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertIndexMode(additionalIndexSettings, IndexMode.LOGSDB.getName());
    }

    public void testCaseSensitivity() throws IOException {
        final LogsdbIndexModeSettingsProvider provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", true).build()
        );

        final Settings additionalIndexSettings = provider.getAdditionalIndexSettings(
            null,
            "LOGS-apache-production",
            null,
            Metadata.EMPTY_METADATA,
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.EMPTY,
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertTrue(additionalIndexSettings.isEmpty());
    }

    public void testMultipleHyphensInDataStreamName() throws IOException {
        final LogsdbIndexModeSettingsProvider provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", true).build()
        );

        final Settings additionalIndexSettings = provider.getAdditionalIndexSettings(
            null,
            "logs-apache-production-eu",
            null,
            Metadata.EMPTY_METADATA,
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.EMPTY,
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertIndexMode(additionalIndexSettings, IndexMode.LOGSDB.getName());
    }

    public void testBeforeAndAFterSettingUpdate() throws IOException {
        final LogsdbIndexModeSettingsProvider provider = new LogsdbIndexModeSettingsProvider(
            syntheticSourceLicenseService,
            Settings.builder().put("cluster.logsdb.enabled", false).build()
        );

        final Settings beforeSettings = provider.getAdditionalIndexSettings(
            null,
            "logs-apache-production",
            null,
            buildMetadata(List.of("*"), List.of("logs@settings")),
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.EMPTY,
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertTrue(beforeSettings.isEmpty());

        provider.updateClusterIndexModeLogsdbEnabled(true);

        final Settings afterSettings = provider.getAdditionalIndexSettings(
            null,
            "logs-apache-production",
            null,
            buildMetadata(List.of("*"), List.of("logs@settings")),
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.EMPTY,
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertIndexMode(afterSettings, IndexMode.LOGSDB.getName());

        provider.updateClusterIndexModeLogsdbEnabled(false);

        final Settings laterSettings = provider.getAdditionalIndexSettings(
            null,
            "logs-apache-production",
            null,
            buildMetadata(List.of("*"), List.of("logs@settings")),
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            Settings.EMPTY,
            List.of(new CompressedXContent(DEFAULT_MAPPING))
        );

        assertTrue(laterSettings.isEmpty());
    }

    private static Metadata buildMetadata(final List<String> indexPatterns, final List<String> componentTemplates) throws IOException {
        final Template template = new Template(Settings.EMPTY, new CompressedXContent(DEFAULT_MAPPING), null);
        final ComposableIndexTemplate composableTemplate = ComposableIndexTemplate.builder()
            .indexPatterns(indexPatterns)
            .template(template)
            .componentTemplates(componentTemplates)
            .priority(1_000L)
            .version(1L)
            .build();
        return Metadata.builder()
            .putCustom(ComposableIndexTemplateMetadata.TYPE, new ComposableIndexTemplateMetadata(Map.of("composable", composableTemplate)))
            .build();
    }

    private void assertIndexMode(final Settings settings, final String expectedIndexMode) {
        assertEquals(expectedIndexMode, settings.get(IndexSettings.MODE.getKey()));
    }

    public void testNewIndexHasSyntheticSourceUsage() throws IOException {
        String dataStreamName = DATA_STREAM_NAME;
        String indexName = DataStream.getDefaultBackingIndexName(dataStreamName, 0);
        Settings settings = Settings.EMPTY;
        LogsdbIndexModeSettingsProvider provider = withSyntheticSourceDemotionSupport(false);
        {
            String mapping = """
                {
                    "_doc": {
                        "_source": {
                            "mode": "synthetic"
                        },
                        "properties": {
                            "my_field": {
                                "type": "keyword"
                            }
                        }
                    }
                }
                """;
            boolean result = provider.getMappingHints(indexName, null, settings, List.of(new CompressedXContent(mapping)))
                .hasSyntheticSourceUsage();
            assertFalse("_source.mode is a noop", result);
            assertThat(newMapperServiceCounter.get(), equalTo(1));
            assertWarnings(SourceFieldMapper.DEPRECATION_WARNING);
        }
        {
            String mapping;
            boolean withSourceMode = randomBoolean();
            if (withSourceMode) {
                mapping = """
                    {
                        "_doc": {
                            "_source": {
                                "mode": "stored"
                            },
                            "properties": {
                                "my_field": {
                                    "type": "keyword"
                                }
                            }
                        }
                    }
                    """;
            } else {
                mapping = """
                    {
                        "_doc": {
                            "properties": {
                                "my_field": {
                                    "type": "keyword"
                                }
                            }
                        }
                    }
                    """;
            }
            boolean result = provider.getMappingHints(indexName, null, settings, List.of(new CompressedXContent(mapping)))
                .hasSyntheticSourceUsage();
            assertFalse(result);
            assertThat(newMapperServiceCounter.get(), equalTo(2));
            if (withSourceMode) {
                assertWarnings(SourceFieldMapper.DEPRECATION_WARNING);
            }
        }
    }

    public void testValidateIndexName() throws IOException {
        String indexName = "validate-index-name";
        String mapping = """
            {
                "_doc": {
                    "_source": {
                        "mode": "synthetic"
                    },
                    "properties": {
                        "my_field": {
                            "type": "keyword"
                        }
                    }
                }
            }
            """;
        Settings settings = Settings.EMPTY;
        LogsdbIndexModeSettingsProvider provider = withSyntheticSourceDemotionSupport(false);
        boolean result = provider.getMappingHints(indexName, null, settings, List.of(new CompressedXContent(mapping)))
            .hasSyntheticSourceUsage();
        assertFalse(result);
    }

    public void testNewIndexHasSyntheticSourceUsageLogsdbIndex() throws IOException {
        String dataStreamName = DATA_STREAM_NAME;
        String indexName = DataStream.getDefaultBackingIndexName(dataStreamName, 0);
        String mapping = """
            {
                "_doc": {
                    "properties": {
                        "my_field": {
                            "type": "keyword"
                        }
                    }
                }
            }
            """;
        LogsdbIndexModeSettingsProvider provider = withSyntheticSourceDemotionSupport(false);
        {
            Settings settings = Settings.builder().put("index.mode", "logsdb").build();
            boolean result = provider.getMappingHints(indexName, null, settings, List.of(new CompressedXContent(mapping)))
                .hasSyntheticSourceUsage();
            assertTrue(result);
            assertThat(newMapperServiceCounter.get(), equalTo(1));
        }
        {
            Settings settings = Settings.builder().put("index.mode", "logsdb").build();
            boolean result = provider.getMappingHints(indexName, null, settings, List.of()).hasSyntheticSourceUsage();
            assertTrue(result);
            assertThat(newMapperServiceCounter.get(), equalTo(2));
        }
        {
            boolean result = provider.getMappingHints(indexName, null, Settings.EMPTY, List.of()).hasSyntheticSourceUsage();
            assertFalse(result);
            assertThat(newMapperServiceCounter.get(), equalTo(3));
        }
        {
            boolean result = provider.getMappingHints(indexName, null, Settings.EMPTY, List.of(new CompressedXContent(mapping)))
                .hasSyntheticSourceUsage();
            assertFalse(result);
            assertThat(newMapperServiceCounter.get(), equalTo(4));
        }
    }

    public void testNewIndexHasSyntheticSourceUsageTimeSeries() throws IOException {
        String dataStreamName = DATA_STREAM_NAME;
        String indexName = DataStream.getDefaultBackingIndexName(dataStreamName, 0);
        String mapping = """
            {
                "_doc": {
                    "properties": {
                        "my_field": {
                            "type": "keyword",
                            "time_series_dimension": true
                        }
                    }
                }
            }
            """;
        LogsdbIndexModeSettingsProvider provider = withSyntheticSourceDemotionSupport(false);
        {
            Settings settings = Settings.builder().put("index.mode", "time_series").put("index.routing_path", "my_field").build();
            boolean result = provider.getMappingHints(indexName, null, settings, List.of(new CompressedXContent(mapping)))
                .hasSyntheticSourceUsage();
            assertTrue(result);
        }
        {
            Settings settings = Settings.builder().put("index.mode", "time_series").put("index.routing_path", "my_field").build();
            boolean result = provider.getMappingHints(indexName, null, settings, List.of()).hasSyntheticSourceUsage();
            assertTrue(result);
        }
        {
            boolean result = provider.getMappingHints(indexName, null, Settings.EMPTY, List.of()).hasSyntheticSourceUsage();
            assertFalse(result);
        }
        {
            boolean result = provider.getMappingHints(indexName, null, Settings.EMPTY, List.of(new CompressedXContent(mapping)))
                .hasSyntheticSourceUsage();
            assertFalse(result);
        }
    }

    public void testNewIndexHasSyntheticSourceUsage_invalidSettings() throws IOException {
        String dataStreamName = DATA_STREAM_NAME;
        String indexName = DataStream.getDefaultBackingIndexName(dataStreamName, 0);
        Settings settings = Settings.builder().put("index.soft_deletes.enabled", false).build();
        LogsdbIndexModeSettingsProvider provider = withSyntheticSourceDemotionSupport(false);
        {
            String mapping = """
                {
                    "_doc": {
                        "_source": {
                            "mode": "synthetic"
                        },
                        "properties": {
                            "my_field": {
                                "type": "keyword"
                            }
                        }
                    }
                }
                """;
            boolean result = provider.getMappingHints(indexName, null, settings, List.of(new CompressedXContent(mapping)))
                .hasSyntheticSourceUsage();
            assertFalse(result);
            assertThat(newMapperServiceCounter.get(), equalTo(1));
        }
        {
            String mapping = """
                {
                    "_doc": {
                        "properties": {
                            "my_field": {
                                "type": "keyword"
                            }
                        }
                    }
                }
                """;
            boolean result = provider.getMappingHints(indexName, null, settings, List.of(new CompressedXContent(mapping)))
                .hasSyntheticSourceUsage();
            assertFalse(result);
            assertThat(newMapperServiceCounter.get(), equalTo(2));
        }
    }

    public void testGetAdditionalIndexSettingsDowngradeFromSyntheticSource() throws IOException {
        String dataStreamName = DATA_STREAM_NAME;
        Metadata.Builder mb = Metadata.builder(
            DataStreamTestHelper.getClusterStateWithDataStreams(
                List.of(Tuple.tuple(dataStreamName, 1)),
                List.of(),
                Instant.now().toEpochMilli(),
                builder().build(),
                1
            ).getMetadata()
        );
        Metadata metadata = mb.build();
        LogsdbIndexModeSettingsProvider provider = withSyntheticSourceDemotionSupport(false);
        Settings settings = builder().put(IndexSettings.INDEX_MAPPER_SOURCE_MODE_SETTING.getKey(), SourceFieldMapper.Mode.SYNTHETIC)
            .build();

        Settings result = provider.getAdditionalIndexSettings(
            DataStream.getDefaultBackingIndexName(dataStreamName, 2),
            dataStreamName,
            null,
            metadata,
            Instant.ofEpochMilli(1L),
            settings,
            List.of()
        );
        assertThat(result.size(), equalTo(0));
        assertThat(newMapperServiceCounter.get(), equalTo(1));

        syntheticSourceLicenseService.setSyntheticSourceFallback(true);
        result = provider.getAdditionalIndexSettings(
            DataStream.getDefaultBackingIndexName(dataStreamName, 2),
            dataStreamName,
            null,
            metadata,
            Instant.ofEpochMilli(1L),
            settings,
            List.of()
        );
        assertThat(result.size(), equalTo(1));
        assertEquals(SourceFieldMapper.Mode.STORED, IndexSettings.INDEX_MAPPER_SOURCE_MODE_SETTING.get(result));
        assertThat(newMapperServiceCounter.get(), equalTo(2));

        result = provider.getAdditionalIndexSettings(
            DataStream.getDefaultBackingIndexName(dataStreamName, 2),
            dataStreamName,
            IndexMode.TIME_SERIES,
            metadata,
            Instant.ofEpochMilli(1L),
            settings,
            List.of()
        );
        assertThat(result.size(), equalTo(1));
        assertEquals(SourceFieldMapper.Mode.STORED, IndexSettings.INDEX_MAPPER_SOURCE_MODE_SETTING.get(result));
        assertThat(newMapperServiceCounter.get(), equalTo(3));

        result = provider.getAdditionalIndexSettings(
            DataStream.getDefaultBackingIndexName(dataStreamName, 2),
            dataStreamName,
            IndexMode.LOGSDB,
            metadata,
            Instant.ofEpochMilli(1L),
            settings,
            List.of()
        );
        assertThat(result.size(), equalTo(3));
        assertEquals(SourceFieldMapper.Mode.STORED, IndexSettings.INDEX_MAPPER_SOURCE_MODE_SETTING.get(result));
        assertTrue(IndexSettings.LOGSDB_SORT_ON_HOST_NAME.get(result));
        assertTrue(IndexSettings.LOGSDB_ADD_HOST_NAME_FIELD.get(result));
        assertThat(newMapperServiceCounter.get(), equalTo(4));
    }

    public void testGetAdditionalIndexSettingsDowngradeFromSyntheticSourceFileMatch() throws IOException {
        syntheticSourceLicenseService.setSyntheticSourceFallback(true);
        LogsdbIndexModeSettingsProvider provider = withSyntheticSourceDemotionSupport(true);
        final Settings settings = Settings.EMPTY;

        String dataStreamName = DATA_STREAM_NAME;
        Metadata.Builder mb = Metadata.builder(
            DataStreamTestHelper.getClusterStateWithDataStreams(
                List.of(Tuple.tuple(dataStreamName, 1)),
                List.of(),
                Instant.now().toEpochMilli(),
                builder().build(),
                1
            ).getMetadata()
        );
        Metadata metadata = mb.build();
        Settings result = provider.getAdditionalIndexSettings(
            DataStream.getDefaultBackingIndexName(dataStreamName, 2),
            dataStreamName,
            null,
            metadata,
            Instant.ofEpochMilli(1L),
            settings,
            List.of()
        );
        assertThat(result.size(), equalTo(0));

        dataStreamName = "logs-app1-0";
        mb = Metadata.builder(
            DataStreamTestHelper.getClusterStateWithDataStreams(
                List.of(Tuple.tuple(dataStreamName, 1)),
                List.of(),
                Instant.now().toEpochMilli(),
                builder().build(),
                1
            ).getMetadata()
        );
        metadata = mb.build();

        result = provider.getAdditionalIndexSettings(
            DataStream.getDefaultBackingIndexName(dataStreamName, 2),
            dataStreamName,
            null,
            metadata,
            Instant.ofEpochMilli(1L),
            settings,
            List.of()
        );
        assertThat(result.size(), equalTo(4));
        assertEquals(SourceFieldMapper.Mode.STORED, IndexSettings.INDEX_MAPPER_SOURCE_MODE_SETTING.get(result));
        assertEquals(IndexMode.LOGSDB, IndexSettings.MODE.get(result));
        assertTrue(IndexSettings.LOGSDB_SORT_ON_HOST_NAME.get(result));
        assertTrue(IndexSettings.LOGSDB_ADD_HOST_NAME_FIELD.get(result));

        result = provider.getAdditionalIndexSettings(
            DataStream.getDefaultBackingIndexName(dataStreamName, 2),
            dataStreamName,
            null,
            metadata,
            Instant.ofEpochMilli(1L),
            builder().put(IndexSettings.MODE.getKey(), IndexMode.STANDARD.toString()).build(),
            List.of()
        );
        assertThat(result.size(), equalTo(0));
    }

    public void testRoutingPathOnSortFields() throws Exception {
        var settings = Settings.builder()
            .put(IndexSortConfig.INDEX_SORT_FIELD_SETTING.getKey(), "host,message")
            .put(IndexSettings.LOGSDB_ROUTE_ON_SORT_FIELDS.getKey(), true)
            .build();
        Settings result = generateLogsdbSettings(settings);
        assertThat(IndexMetadata.INDEX_ROUTING_PATH.get(result), contains("host", "message"));
    }

    public void testRoutingPathOnSortFieldsFilterTimestamp() throws Exception {
        var settings = Settings.builder()
            .put(IndexSortConfig.INDEX_SORT_FIELD_SETTING.getKey(), "host,message,@timestamp")
            .put(IndexSettings.LOGSDB_ROUTE_ON_SORT_FIELDS.getKey(), true)
            .build();
        Settings result = generateLogsdbSettings(settings);
        assertThat(IndexMetadata.INDEX_ROUTING_PATH.get(result), contains("host", "message"));
    }

    public void testRoutingPathOnSortSingleField() throws Exception {
        var settings = Settings.builder()
            .put(IndexSortConfig.INDEX_SORT_FIELD_SETTING.getKey(), "host")
            .put(IndexSettings.LOGSDB_ROUTE_ON_SORT_FIELDS.getKey(), true)
            .build();
        Exception e = expectThrows(IllegalStateException.class, () -> generateLogsdbSettings(settings));
        assertThat(
            e.getMessage(),
            equalTo(
                "data stream ["
                    + DATA_STREAM_NAME
                    + "] in logsdb mode and with [index.logsdb.route_on_sort_fields] index setting has only 1 sort fields "
                    + "(excluding timestamp), needs at least 2"
            )
        );
    }

    public void testExplicitRoutingPathMatchesSortFields() throws Exception {
        var settings = Settings.builder()
            .put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB)
            .put(IndexSortConfig.INDEX_SORT_FIELD_SETTING.getKey(), "host,message,@timestamp")
            .put(IndexMetadata.INDEX_ROUTING_PATH.getKey(), "host,message")
            .put(IndexSettings.LOGSDB_ROUTE_ON_SORT_FIELDS.getKey(), true)
            .build();
        Settings result = generateLogsdbSettings(settings);
        assertTrue(result.isEmpty());
    }

    public void testExplicitRoutingPathDoesNotMatchSortFields() throws Exception {
        var settings = Settings.builder()
            .put(IndexSortConfig.INDEX_SORT_FIELD_SETTING.getKey(), "host,message,@timestamp")
            .put(IndexMetadata.INDEX_ROUTING_PATH.getKey(), "host,message,foo")
            .put(IndexSettings.LOGSDB_ROUTE_ON_SORT_FIELDS.getKey(), true)
            .build();
        Exception e = expectThrows(IllegalStateException.class, () -> generateLogsdbSettings(settings));
        assertThat(
            e.getMessage(),
            equalTo(
                "data stream ["
                    + DATA_STREAM_NAME
                    + "] in logsdb mode and with [index."
                    + "logsdb.route_on_sort_fields] index setting has mismatching sort "
                    + "and routing fields, [index.routing_path:[host, message, foo]], [index.sort.fields:[host, message]]"
            )
        );
    }

    public void testSortAndHostNamePropagateValue() throws Exception {
        var settings = Settings.builder()
            .put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB)
            .put(IndexSettings.LOGSDB_SORT_ON_HOST_NAME.getKey(), true)
            .put(IndexSettings.LOGSDB_ADD_HOST_NAME_FIELD.getKey(), true)
            .build();
        Settings result = generateLogsdbSettings(settings);
        assertTrue(IndexSettings.LOGSDB_SORT_ON_HOST_NAME.get(result));
        assertTrue(IndexSettings.LOGSDB_ADD_HOST_NAME_FIELD.get(result));
        assertEquals(0, newMapperServiceCounter.get());
    }

    public void testSortAndHostNameWithCustomSortConfig() throws Exception {
        var settings = Settings.builder()
            .put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB)
            .put(IndexSortConfig.INDEX_SORT_FIELD_SETTING.getKey(), "foo,bar")
            .build();
        Settings result = generateLogsdbSettings(settings);
        assertFalse(IndexSettings.LOGSDB_SORT_ON_HOST_NAME.get(result));
        assertFalse(IndexSettings.LOGSDB_ADD_HOST_NAME_FIELD.get(result));
        assertEquals(0, newMapperServiceCounter.get());
    }

    public void testSortAndHostNameKeyword() throws Exception {
        var settings = Settings.builder().put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB).build();
        var mappings = """
            {
                "_doc": {
                    "properties": {
                        "@timestamp": {
                            "type": "date"
                        },
                        "host.name": {
                            "type": "keyword"
                        }
                    }
                }
            }
            """;
        Settings result = generateLogsdbSettings(settings, mappings);
        assertTrue(IndexSettings.LOGSDB_SORT_ON_HOST_NAME.get(result));
        assertFalse(IndexSettings.LOGSDB_ADD_HOST_NAME_FIELD.get(result));
        assertEquals(1, newMapperServiceCounter.get());
    }

    public void testSortAndHostNameKeywordNoDocvalues() throws Exception {
        var settings = Settings.builder().put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB).build();
        var mappings = """
            {
                "_doc": {
                    "properties": {
                        "@timestamp": {
                            "type": "date"
                        },
                        "host.name": {
                            "type": "keyword",
                            "doc_values": false
                        }
                    }
                }
            }
            """;
        Settings result = generateLogsdbSettings(settings, mappings);
        assertFalse(IndexSettings.LOGSDB_SORT_ON_HOST_NAME.get(result));
        assertFalse(IndexSettings.LOGSDB_ADD_HOST_NAME_FIELD.get(result));
        assertEquals(1, newMapperServiceCounter.get());
    }

    public void testSortAndHostNameInteger() throws Exception {
        var settings = Settings.builder().put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB).build();
        var mappings = """
            {
                "_doc": {
                    "properties": {
                        "@timestamp": {
                            "type": "date"
                        },
                        "host.name": {
                            "type": "integer"
                        }
                    }
                }
            }
            """;
        Settings result = generateLogsdbSettings(settings, mappings);
        assertTrue(IndexSettings.LOGSDB_SORT_ON_HOST_NAME.get(result));
        assertFalse(IndexSettings.LOGSDB_ADD_HOST_NAME_FIELD.get(result));
        assertEquals(1, newMapperServiceCounter.get());
    }

    public void testSortAndHostNameIntegerNoDocvalues() throws Exception {
        var settings = Settings.builder().put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB).build();
        var mappings = """
            {
                "_doc": {
                    "properties": {
                        "@timestamp": {
                            "type": "date"
                        },
                        "host.name": {
                            "type": "integer",
                            "doc_values": false
                        }
                    }
                }
            }
            """;
        Settings result = generateLogsdbSettings(settings, mappings);
        assertFalse(IndexSettings.LOGSDB_SORT_ON_HOST_NAME.get(result));
        assertFalse(IndexSettings.LOGSDB_ADD_HOST_NAME_FIELD.get(result));
        assertEquals(1, newMapperServiceCounter.get());
    }

    public void testSortAndHostNameBoolean() throws Exception {
        var settings = Settings.builder().put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB).build();
        var mappings = """
            {
                "_doc": {
                    "properties": {
                        "@timestamp": {
                            "type": "date"
                        },
                        "host.name": {
                            "type": "boolean"
                        }
                    }
                }
            }
            """;
        Settings result = generateLogsdbSettings(settings, mappings);
        assertFalse(IndexSettings.LOGSDB_SORT_ON_HOST_NAME.get(result));
        assertFalse(IndexSettings.LOGSDB_ADD_HOST_NAME_FIELD.get(result));
        assertEquals(1, newMapperServiceCounter.get());
    }

    public void testSortAndHostObject() throws Exception {
        var settings = Settings.builder().put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB).build();
        var mappings = """
            {
                "_doc": {
                    "properties": {
                        "@timestamp": {
                            "type": "date"
                        },
                        "host": {
                            "type": "object"
                        }
                    }
                }
            }
            """;
        Settings result = generateLogsdbSettings(settings, mappings);
        assertTrue(IndexSettings.LOGSDB_SORT_ON_HOST_NAME.get(result));
        assertTrue(IndexSettings.LOGSDB_ADD_HOST_NAME_FIELD.get(result));
        assertEquals(1, newMapperServiceCounter.get());
    }

    public void testSortAndHostField() throws Exception {
        var settings = Settings.builder().put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB).build();
        var mappings = """
            {
                "_doc": {
                    "properties": {
                        "@timestamp": {
                            "type": "date"
                        },
                        "host": {
                            "type": "keyword"
                        }
                    }
                }
            }
            """;
        Settings result = generateLogsdbSettings(settings, mappings);
        assertFalse(IndexSettings.LOGSDB_SORT_ON_HOST_NAME.get(result));
        assertFalse(IndexSettings.LOGSDB_ADD_HOST_NAME_FIELD.get(result));
        assertEquals(1, newMapperServiceCounter.get());
    }

    public void testSortAndHostFieldSubobjectsFalse() throws Exception {
        var settings = Settings.builder().put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB).build();
        var mappings = """
            {
                "_doc": {
                    "subobjects": false,
                    "properties": {
                        "@timestamp": {
                            "type": "date"
                        },
                        "host": {
                            "type": "keyword"
                        }
                    }
                }
            }
            """;
        Settings result = generateLogsdbSettings(settings, mappings);
        assertTrue(IndexSettings.LOGSDB_SORT_ON_HOST_NAME.get(result));
        assertTrue(IndexSettings.LOGSDB_ADD_HOST_NAME_FIELD.get(result));
        assertEquals(1, newMapperServiceCounter.get());
    }

    public void testSortAndHostNameObject() throws Exception {
        var settings = Settings.builder().put(IndexSettings.MODE.getKey(), IndexMode.LOGSDB).build();
        var mappings = """
            {
                "_doc": {
                    "properties": {
                        "@timestamp": {
                            "type": "date"
                        },
                        "host.name.sub": {
                            "type": "keyword"
                        }
                    }
                }
            }
            """;
        Settings result = generateLogsdbSettings(settings, mappings);
        assertFalse(IndexSettings.LOGSDB_SORT_ON_HOST_NAME.get(result));
        assertFalse(IndexSettings.LOGSDB_ADD_HOST_NAME_FIELD.get(result));
        assertEquals(1, newMapperServiceCounter.get());
    }
}
