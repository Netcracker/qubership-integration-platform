package org.qubership.integration.platform.runtime.catalog.configuration;

import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactoryBuilder;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.util.StringQuotingChecker;
import org.yaml.snakeyaml.DumperOptions;

import java.io.IOException;
import java.io.Writer;

/**
 * Writes a multi-line string double-quoted instead of as a literal block when it contains U+0085, U+2028, or
 * U+2029, because a YAML reader turns those characters into line feeds inside a literal block.
 */
public class MinimizedQuotesYamlFactory extends YAMLFactory {
    public MinimizedQuotesYamlFactory(YAMLFactoryBuilder builder) {
        super(builder);
    }

    private MinimizedQuotesYamlFactory(MinimizedQuotesYamlFactory src) {
        super(src, null);
    }

    @Override
    public YAMLFactory copy() {
        return new MinimizedQuotesYamlFactory(this);
    }

    @Override
    protected YAMLGenerator _createGenerator(Writer out, IOContext ctxt) throws IOException {
        return _dumperOptions == null
                ? new Generator(ctxt, _generatorFeatures, _yamlGeneratorFeatures, _quotingChecker, _objectCodec, out,
                        _version)
                : new Generator(ctxt, _generatorFeatures, _yamlGeneratorFeatures, _quotingChecker, _objectCodec, out,
                        _dumperOptions);
    }

    private static final class Generator extends YAMLGenerator {
        Generator(IOContext ctxt, int jsonFeatures, int yamlFeatures, StringQuotingChecker quotingChecker,
                ObjectCodec codec, Writer out, DumperOptions.Version version) throws IOException {
            super(ctxt, jsonFeatures, yamlFeatures, quotingChecker, codec, out, version);
        }

        Generator(IOContext ctxt, int jsonFeatures, int yamlFeatures, StringQuotingChecker quotingChecker,
                ObjectCodec codec, Writer out, DumperOptions dumperOptions) throws IOException {
            super(ctxt, jsonFeatures, yamlFeatures, quotingChecker, codec, out, dumperOptions);
        }

        @Override
        public void writeString(String text) throws IOException {
            if (text != null && isEnabled(Feature.MINIMIZE_QUOTES) && text.indexOf('\n') >= 0
                    && YamlStringQuotingChecker.containsUnicodeLineBreak(text)) {
                _verifyValueWrite("write String value");
                _writeScalar(text, "string", DumperOptions.ScalarStyle.DOUBLE_QUOTED);
                return;
            }
            super.writeString(text);
        }
    }
}
