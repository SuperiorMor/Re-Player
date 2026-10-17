import React, { useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Platform,
} from 'react-native';
import * as Clipboard from 'expo-clipboard';
import { ParsedPromo } from '../types/promo';
import { THEME } from '../constants/theme';

interface ExtractedPromoCardProps {
  promo: ParsedPromo;
  onAddToCalendar: (promo: ParsedPromo) => void;
  onEdit: (promo: ParsedPromo) => void;
  onSave?: (promo: ParsedPromo) => void;
  isSaved?: boolean;
}

export const ExtractedPromoCard: React.FC<ExtractedPromoCardProps> = ({
  promo,
  onAddToCalendar,
  onEdit,
  onSave,
  isSaved = false,
}) => {
  const [codeCopied, setCodeCopied] = useState(false);

  const handleCopyCode = async () => {
    if (promo.promoCode) {
      await Clipboard.setStringAsync(promo.promoCode);
      setCodeCopied(true);
      setTimeout(() => setCodeCopied(false), 2000);
    }
  };

  const getStatusBadge = () => {
    if (promo.isExpired) {
      return {
        text: 'Срок истёк',
        bg: THEME.colors.expiredLight,
        color: THEME.colors.expired,
        border: THEME.colors.expiredBorder,
      };
    }
    if (promo.daysRemaining !== null) {
      if (promo.daysRemaining === 0) {
        return {
          text: '⚡ Истекает сегодня!',
          bg: THEME.colors.expiringLight,
          color: THEME.colors.expiring,
          border: THEME.colors.expiringBorder,
        };
      }
      if (promo.daysRemaining === 1) {
        return {
          text: '⏳ Истекает завтра',
          bg: THEME.colors.expiringLight,
          color: THEME.colors.expiring,
          border: THEME.colors.expiringBorder,
        };
      }
      if (promo.daysRemaining <= 3) {
        return {
          text: `⏳ Осталось ${promo.daysRemaining} дня`,
          bg: THEME.colors.expiringLight,
          color: THEME.colors.expiring,
          border: THEME.colors.expiringBorder,
        };
      }
      return {
        text: `✓ Осталось ${promo.daysRemaining} дн.`,
        bg: THEME.colors.activeLight,
        color: THEME.colors.active,
        border: THEME.colors.activeBorder,
      };
    }
    return {
      text: 'Активна',
      bg: THEME.colors.activeLight,
      color: THEME.colors.active,
      border: THEME.colors.activeBorder,
    };
  };

  const status = getStatusBadge();

  return (
    <View style={styles.card}>
      {/* Top Banner: Store & Status */}
      <View style={styles.topRow}>
        <View style={styles.storeInfo}>
          <View style={styles.storeAvatar}>
            <Text style={styles.storeAvatarText}>
              {promo.store.charAt(0).toUpperCase()}
            </Text>
          </View>
          <View>
            <Text style={styles.storeLabel}>Магазин / Сервис</Text>
            <Text style={styles.storeName}>{promo.store}</Text>
          </View>
        </View>

        <View
          style={[
            styles.statusBadge,
            { backgroundColor: status.bg, borderColor: status.border },
          ]}
        >
          <Text style={[styles.statusText, { color: status.color }]}>
            {status.text}
          </Text>
        </View>
      </View>

      {/* Main Promo Highlight: Discount & Code */}
      <View style={styles.highlightSection}>
        <View style={styles.discountBadge}>
          <Text style={styles.discountLabel}>Скидка</Text>
          <Text style={styles.discountValue}>{promo.discount}</Text>
        </View>

        <View style={styles.promoCodeBox}>
          <Text style={styles.promoCodeLabel}>Промокод</Text>
          {promo.promoCode ? (
            <TouchableOpacity
              style={styles.codeButton}
              onPress={handleCopyCode}
              activeOpacity={0.7}
            >
              <Text style={styles.codeText}>{promo.promoCode}</Text>
              <View style={styles.copyPill}>
                <Text style={styles.copyPillText}>
                  {codeCopied ? '✓ Скопирован' : 'Копировать'}
                </Text>
              </View>
            </TouchableOpacity>
          ) : (
            <View style={styles.noCodeBox}>
              <Text style={styles.noCodeText}>Автоскидка (без кода)</Text>
            </View>
          )}
        </View>
      </View>

      {/* Detailed Parameters Grid */}
      <View style={styles.gridSection}>
        {/* Expiration Date */}
        <View style={styles.gridItem}>
          <Text style={styles.gridLabel}>📅 Срок действия до</Text>
          <Text style={styles.gridValue}>
            {promo.expirationDateDisplay}
          </Text>
        </View>

        {/* Conditions */}
        <View style={styles.gridItem}>
          <Text style={styles.gridLabel}>📋 Условия</Text>
          <Text style={styles.gridValue} numberOfLines={2}>
            {promo.conditions}
          </Text>
        </View>
      </View>

      {/* Confidence indicator */}
      <View style={styles.confidenceRow}>
        <View style={styles.confidenceBarBg}>
          <View
            style={[
              styles.confidenceBarFill,
              { width: `${promo.confidenceScore}%` },
            ]}
          />
        </View>
        <Text style={styles.confidenceText}>
          Точность распознавания: {promo.confidenceScore}%
        </Text>
      </View>

      {/* Action Buttons */}
      <View style={styles.buttonsContainer}>
        {/* Main Action: Put into Calendar */}
        <TouchableOpacity
          style={[
            styles.calendarButton,
            promo.addedToCalendar && styles.calendarButtonAdded,
          ]}
          onPress={() => onAddToCalendar(promo)}
          activeOpacity={0.8}
        >
          <Text style={styles.calendarButtonText}>
            {promo.addedToCalendar
              ? '✓ В календаре (обновить)'
              : '🗓️ Поставить в календарь'}
          </Text>
        </TouchableOpacity>

        {/* Secondary Actions */}
        <View style={styles.secondaryRow}>
          <TouchableOpacity
            style={styles.secondaryButton}
            onPress={() => onEdit(promo)}
            activeOpacity={0.7}
          >
            <Text style={styles.secondaryButtonText}>✏️ Изменить</Text>
          </TouchableOpacity>

          {onSave && (
            <TouchableOpacity
              style={[
                styles.secondaryButton,
                isSaved && styles.secondaryButtonSaved,
              ]}
              onPress={() => onSave(promo)}
              activeOpacity={0.7}
            >
              <Text
                style={[
                  styles.secondaryButtonText,
                  isSaved && styles.secondaryButtonSavedText,
                ]}
              >
                {isSaved ? '★ Сохранено' : '☆ В сохранённые'}
              </Text>
            </TouchableOpacity>
          )}
        </View>
      </View>
    </View>
  );
};

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
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.1,
    shadowRadius: 14,
    elevation: 4,
  },
  topRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: THEME.spacing.md,
  },
  storeInfo: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  storeAvatar: {
    width: 38,
    height: 38,
    borderRadius: 10,
    backgroundColor: THEME.colors.promoBadge,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 10,
  },
  storeAvatarText: {
    color: THEME.colors.promoPrimary,
    fontSize: 18,
    fontWeight: '800',
  },
  storeLabel: {
    fontSize: 11,
    color: THEME.colors.textMuted,
    fontWeight: '500',
  },
  storeName: {
    fontSize: 16,
    fontWeight: '800',
    color: THEME.colors.textPrimary,
  },
  statusBadge: {
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: THEME.radii.full,
    borderWidth: 1,
  },
  statusText: {
    fontSize: 12,
    fontWeight: '700',
  },
  highlightSection: {
    flexDirection: 'row',
    backgroundColor: THEME.colors.background,
    borderRadius: THEME.radii.md,
    padding: THEME.spacing.md,
    marginBottom: THEME.spacing.md,
    borderWidth: 1,
    borderColor: THEME.colors.border,
    gap: 12,
  },
  discountBadge: {
    flex: 1,
    justifyContent: 'center',
    borderRightWidth: 1,
    borderRightColor: THEME.colors.border,
    paddingRight: 8,
  },
  discountLabel: {
    fontSize: 11,
    color: THEME.colors.textMuted,
    fontWeight: '600',
    textTransform: 'uppercase',
  },
  discountValue: {
    fontSize: 22,
    fontWeight: '900',
    color: THEME.colors.kashkaAccent,
    marginTop: 2,
  },
  promoCodeBox: {
    flex: 1.4,
    justifyContent: 'center',
  },
  promoCodeLabel: {
    fontSize: 11,
    color: THEME.colors.textMuted,
    fontWeight: '600',
    textTransform: 'uppercase',
  },
  codeButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: THEME.colors.surface,
    borderColor: THEME.colors.promoPrimary,
    borderWidth: 1.2,
    borderRadius: THEME.radii.sm,
    paddingHorizontal: 8,
    paddingVertical: 4,
    marginTop: 4,
  },
  codeText: {
    fontSize: 14,
    fontWeight: '800',
    color: THEME.colors.promoPrimary,
    fontFamily: Platform.OS === 'ios' ? 'Courier' : 'monospace',
  },
  copyPill: {
    backgroundColor: THEME.colors.promoBadge,
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  copyPillText: {
    fontSize: 10,
    fontWeight: '700',
    color: THEME.colors.promoBadgeText,
  },
  noCodeBox: {
    marginTop: 4,
    paddingVertical: 4,
  },
  noCodeText: {
    fontSize: 12,
    color: THEME.colors.textSecondary,
    fontStyle: 'italic',
  },
  gridSection: {
    gap: 8,
    marginBottom: THEME.spacing.md,
  },
  gridItem: {
    backgroundColor: THEME.colors.surfaceSubtle,
    borderRadius: THEME.radii.sm,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  gridLabel: {
    fontSize: 11,
    color: THEME.colors.textSecondary,
    fontWeight: '600',
    marginBottom: 2,
  },
  gridValue: {
    fontSize: 13,
    color: THEME.colors.textPrimary,
    fontWeight: '600',
  },
  confidenceRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: THEME.spacing.lg,
    gap: 8,
  },
  confidenceBarBg: {
    flex: 1,
    height: 4,
    backgroundColor: THEME.colors.border,
    borderRadius: 2,
    overflow: 'hidden',
  },
  confidenceBarFill: {
    height: 4,
    backgroundColor: THEME.colors.active,
    borderRadius: 2,
  },
  confidenceText: {
    fontSize: 11,
    color: THEME.colors.textMuted,
    fontWeight: '500',
  },
  buttonsContainer: {
    gap: 8,
  },
  calendarButton: {
    backgroundColor: THEME.colors.promoPrimary,
    borderRadius: THEME.radii.md,
    paddingVertical: 14,
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: THEME.colors.promoPrimary,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 8,
    elevation: 4,
  },
  calendarButtonAdded: {
    backgroundColor: THEME.colors.active,
    shadowColor: THEME.colors.active,
  },
  calendarButtonText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '800',
    letterSpacing: 0.3,
  },
  secondaryRow: {
    flexDirection: 'row',
    gap: 8,
  },
  secondaryButton: {
    flex: 1,
    backgroundColor: THEME.colors.surfaceSubtle,
    borderColor: THEME.colors.border,
    borderWidth: 1,
    borderRadius: THEME.radii.sm,
    paddingVertical: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  secondaryButtonSaved: {
    backgroundColor: THEME.colors.kashkaWarmBg,
    borderColor: THEME.colors.kashkaAccent,
  },
  secondaryButtonText: {
    color: THEME.colors.textSecondary,
    fontSize: 13,
    fontWeight: '600',
  },
  secondaryButtonSavedText: {
    color: THEME.colors.kashkaWarmText,
    fontWeight: '700',
  },
});
