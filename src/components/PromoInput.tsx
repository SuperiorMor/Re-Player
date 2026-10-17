import React, { useState } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ScrollView,
  Platform,
} from 'react-native';
import * as Clipboard from 'expo-clipboard';
import { THEME } from '../constants/theme';
import { SAMPLE_PROMOS, SamplePromo } from '../constants/samples';

interface PromoInputProps {
  onAnalyze: (text: string) => void;
  isLoading?: boolean;
}

export const PromoInput: React.FC<PromoInputProps> = ({ onAnalyze, isLoading = false }) => {
  const [inputText, setInputText] = useState('');
  const [copiedNotification, setCopiedNotification] = useState(false);

  const handlePaste = async () => {
    try {
      const content = await Clipboard.getStringAsync();
      if (content && content.trim().length > 0) {
        setInputText(content.trim());
        setCopiedNotification(true);
        setTimeout(() => setCopiedNotification(false), 2000);
      }
    } catch {
      // Fallback
    }
  };

  const handleClear = () => {
    setInputText('');
  };

  const handleSelectSample = (sample: SamplePromo) => {
    setInputText(sample.text);
    onAnalyze(sample.text);
  };

  const handleAnalyzePress = () => {
    if (inputText.trim().length > 0) {
      onAnalyze(inputText.trim());
    }
  };

  return (
    <View style={styles.card}>
      {/* Header bar */}
      <View style={styles.cardHeader}>
        <View style={styles.headerLeft}>
          <Text style={styles.cardTitle}>Текст акции или промокод</Text>
          <Text style={styles.cardSubtitle}>
            Вставьте SMS, push, сообщение или баннер
          </Text>
        </View>

        <View style={styles.actionsRow}>
          <TouchableOpacity
            style={styles.actionBtn}
            onPress={handlePaste}
            activeOpacity={0.7}
          >
            <Text style={styles.actionBtnText}>
              {copiedNotification ? '✓ Вставлено' : '📋 Вставить'}
            </Text>
          </TouchableOpacity>

          {inputText.length > 0 && (
            <TouchableOpacity
              style={[styles.actionBtn, styles.clearBtn]}
              onPress={handleClear}
              activeOpacity={0.7}
            >
              <Text style={styles.clearBtnText}>Очистить</Text>
            </TouchableOpacity>
          )}
        </View>
      </View>

      {/* Input text area */}
      <View style={styles.inputWrapper}>
        <TextInput
          style={styles.textInput}
          multiline
          numberOfLines={4}
          placeholder="Например: -30% на доставку от 1800 рублей. Магазин Магнит. Предложение действительно до 21 октября."
          placeholderTextColor={THEME.colors.textMuted}
          value={inputText}
          onChangeText={setInputText}
          textAlignVertical="top"
        />
        {inputText.length > 0 && (
          <Text style={styles.charCount}>
            {inputText.length} {getCharNoun(inputText.length)}
          </Text>
        )}
      </View>

      {/* Quick samples pills */}
      <View style={styles.samplesContainer}>
        <Text style={styles.samplesLabel}>Быстрые примеры для проверки:</Text>
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.samplesScroll}
        >
          {SAMPLE_PROMOS.map(sample => (
            <TouchableOpacity
              key={sample.id}
              style={[
                styles.sampleChip,
                sample.id === 'sample-magnit' && styles.sampleChipFeatured,
              ]}
              onPress={() => handleSelectSample(sample)}
              activeOpacity={0.75}
            >
              <Text
                style={[
                  styles.sampleChipText,
                  sample.id === 'sample-magnit' && styles.sampleChipFeaturedText,
                ]}
              >
                {sample.id === 'sample-magnit' ? '⭐ ' : ''}
                {sample.title}
              </Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      </View>

      {/* Main Submit Button */}
      <TouchableOpacity
        style={[
          styles.submitButton,
          (!inputText.trim() || isLoading) && styles.submitButtonDisabled,
        ]}
        onPress={handleAnalyzePress}
        disabled={!inputText.trim() || isLoading}
        activeOpacity={0.8}
      >
        <Text style={styles.submitButtonText}>
          {isLoading ? 'Анализируем...' : '⚡ Распознать и разобрать'}
        </Text>
      </TouchableOpacity>
    </View>
  );
};

function getCharNoun(n: number) {
  let count = Math.abs(n) % 100;
  if (count >= 5 && count <= 20) return 'символов';
  count %= 10;
  if (count === 1) return 'символ';
  if (count >= 2 && count <= 4) return 'символа';
  return 'символов';
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: THEME.colors.surface,
    borderRadius: THEME.radii.lg,
    padding: THEME.spacing.lg,
    marginHorizontal: THEME.spacing.lg,
    marginTop: THEME.spacing.md,
    borderWidth: 1,
    borderColor: THEME.colors.border,
    shadowColor: THEME.colors.shadow,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.08,
    shadowRadius: 10,
    elevation: 3,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: THEME.spacing.sm,
  },
  headerLeft: {
    flex: 1,
    marginRight: 8,
  },
  cardTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: THEME.colors.textPrimary,
  },
  cardSubtitle: {
    fontSize: 12,
    color: THEME.colors.textMuted,
    marginTop: 2,
  },
  actionsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  actionBtn: {
    backgroundColor: THEME.colors.promoBadge,
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: THEME.radii.sm,
  },
  actionBtnText: {
    color: THEME.colors.promoBadgeText,
    fontSize: 12,
    fontWeight: '600',
  },
  clearBtn: {
    backgroundColor: THEME.colors.surfaceSubtle,
  },
  clearBtnText: {
    color: THEME.colors.textSecondary,
    fontSize: 12,
    fontWeight: '500',
  },
  inputWrapper: {
    position: 'relative',
    marginTop: THEME.spacing.xs,
  },
  textInput: {
    backgroundColor: THEME.colors.background,
    borderColor: THEME.colors.border,
    borderWidth: 1,
    borderRadius: THEME.radii.md,
    padding: THEME.spacing.md,
    paddingBottom: 28,
    fontSize: 14,
    color: THEME.colors.textPrimary,
    minHeight: 105,
    fontFamily: Platform.OS === 'ios' ? 'System' : 'sans-serif',
    lineHeight: 20,
  },
  charCount: {
    position: 'absolute',
    bottom: 8,
    right: 12,
    fontSize: 11,
    color: THEME.colors.textMuted,
  },
  samplesContainer: {
    marginTop: THEME.spacing.md,
  },
  samplesLabel: {
    fontSize: 12,
    fontWeight: '600',
    color: THEME.colors.textSecondary,
    marginBottom: 6,
  },
  samplesScroll: {
    gap: 6,
    paddingVertical: 2,
  },
  sampleChip: {
    backgroundColor: THEME.colors.surfaceSubtle,
    borderColor: THEME.colors.border,
    borderWidth: 1,
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: THEME.radii.full,
  },
  sampleChipFeatured: {
    backgroundColor: THEME.colors.kashkaWarmBg,
    borderColor: THEME.colors.kashkaAccent,
  },
  sampleChipText: {
    fontSize: 12,
    color: THEME.colors.textSecondary,
    fontWeight: '500',
  },
  sampleChipFeaturedText: {
    color: THEME.colors.kashkaWarmText,
    fontWeight: '700',
  },
  submitButton: {
    backgroundColor: THEME.colors.promoPrimary,
    borderRadius: THEME.radii.md,
    paddingVertical: 14,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: THEME.spacing.lg,
    shadowColor: THEME.colors.promoPrimary,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.28,
    shadowRadius: 8,
    elevation: 4,
  },
  submitButtonDisabled: {
    backgroundColor: THEME.colors.border,
    shadowOpacity: 0,
    elevation: 0,
  },
  submitButtonText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '700',
    letterSpacing: 0.3,
  },
});
