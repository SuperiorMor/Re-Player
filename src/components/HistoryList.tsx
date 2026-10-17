import React, { useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  Platform,
} from 'react-native';
import * as Clipboard from 'expo-clipboard';
import { ParsedPromo, PromoStatus } from '../types/promo';
import { THEME } from '../constants/theme';

interface HistoryListProps {
  promos: ParsedPromo[];
  onSelectPromo: (promo: ParsedPromo) => void;
  onAddToCalendar: (promo: ParsedPromo) => void;
  onDeletePromo: (id: string) => void;
}

export const HistoryList: React.FC<HistoryListProps> = ({
  promos,
  onSelectPromo,
  onAddToCalendar,
  onDeletePromo,
}) => {
  const [filter, setFilter] = useState<PromoStatus>('all');
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const handleCopyCode = async (promo: ParsedPromo) => {
    if (promo.promoCode) {
      await Clipboard.setStringAsync(promo.promoCode);
      setCopiedId(promo.id);
      setTimeout(() => setCopiedId(null), 2000);
    }
  };

  const filteredPromos = promos.filter(p => {
    if (filter === 'active') return !p.isExpired;
    if (filter === 'expiring') return !p.isExpired && p.daysRemaining !== null && p.daysRemaining <= 3;
    if (filter === 'expired') return p.isExpired;
    return true;
  });

  return (
    <View style={styles.container}>
      {/* Header and Filter Tabs */}
      <View style={styles.headerRow}>
        <View>
          <Text style={styles.sectionTitle}>Сохранённые акции</Text>
          <Text style={styles.sectionSubtitle}>
            {promos.length} {getPromoNoun(promos.length)} в базе
          </Text>
        </View>

        <View style={styles.filtersScroll}>
          <TouchableOpacity
            style={[styles.filterChip, filter === 'all' && styles.filterChipActive]}
            onPress={() => setFilter('all')}
          >
            <Text style={[styles.filterChipText, filter === 'all' && styles.filterChipTextActive]}>
              Все ({promos.length})
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.filterChip, filter === 'active' && styles.filterChipActive]}
            onPress={() => setFilter('active')}
          >
            <Text style={[styles.filterChipText, filter === 'active' && styles.filterChipTextActive]}>
              Активные
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.filterChip, filter === 'expiring' && styles.filterChipActive]}
            onPress={() => setFilter('expiring')}
          >
            <Text style={[styles.filterChipText, filter === 'expiring' && styles.filterChipTextActive]}>
              ⏳ Скоро
            </Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* List items */}
      {filteredPromos.length === 0 ? (
        <View style={styles.emptyState}>
          <Text style={styles.emptyIcon}>🏷️</Text>
          <Text style={styles.emptyTitle}>Нет акций в этой категории</Text>
          <Text style={styles.emptySubtitle}>
            Вставьте текст скидки выше, чтобы распознать и сохранить
          </Text>
        </View>
      ) : (
        <ScrollView style={styles.listScroll} showsVerticalScrollIndicator={false}>
          {filteredPromos.map(item => (
            <View key={item.id} style={styles.listItem}>
              <TouchableOpacity
                style={styles.itemContent}
                onPress={() => onSelectPromo(item)}
                activeOpacity={0.7}
              >
                <View style={styles.itemTop}>
                  <View style={styles.storeBadge}>
                    <Text style={styles.storeBadgeText}>{item.store}</Text>
                  </View>

                  <View style={styles.discountTag}>
                    <Text style={styles.discountTagText}>{item.discount}</Text>
                  </View>
                </View>

                {item.promoCode && (
                  <View style={styles.codeRow}>
                    <Text style={styles.codePrefix}>Код: </Text>
                    <Text style={styles.codeValue}>{item.promoCode}</Text>
                  </View>
                )}

                <Text style={styles.itemConditions} numberOfLines={1}>
                  {item.conditions}
                </Text>

                <View style={styles.itemBottom}>
                  <Text style={styles.itemDate}>
                    ⏳ До: {item.expirationDateDisplay}
                  </Text>

                  {item.addedToCalendar && (
                    <Text style={styles.inCalBadge}>✓ В календаре</Text>
                  )}
                </View>
              </TouchableOpacity>

              {/* Quick action buttons on the right */}
              <View style={styles.itemActions}>
                {item.promoCode && (
                  <TouchableOpacity
                    style={styles.miniActionBtn}
                    onPress={() => handleCopyCode(item)}
                    activeOpacity={0.7}
                  >
                    <Text style={styles.miniActionText}>
                      {copiedId === item.id ? '✓' : '📋'}
                    </Text>
                  </TouchableOpacity>
                )}

                <TouchableOpacity
                  style={[
                    styles.miniActionBtn,
                    item.addedToCalendar && styles.miniActionBtnActive,
                  ]}
                  onPress={() => onAddToCalendar(item)}
                  activeOpacity={0.7}
                >
                  <Text style={styles.miniActionText}>🗓️</Text>
                </TouchableOpacity>

                <TouchableOpacity
                  style={[styles.miniActionBtn, styles.deleteBtn]}
                  onPress={() => onDeletePromo(item.id)}
                  activeOpacity={0.7}
                >
                  <Text style={styles.deleteBtnText}>✕</Text>
                </TouchableOpacity>
              </View>
            </View>
          ))}
        </ScrollView>
      )}
    </View>
  );
};

function getPromoNoun(n: number) {
  let count = Math.abs(n) % 100;
  if (count >= 5 && count <= 20) return 'акций';
  count %= 10;
  if (count === 1) return 'акция';
  if (count >= 2 && count <= 4) return 'акции';
  return 'акций';
}

const styles = StyleSheet.create({
  container: {
    paddingHorizontal: THEME.spacing.lg,
    marginTop: THEME.spacing.lg,
    paddingBottom: 40,
  },
  headerRow: {
    marginBottom: THEME.spacing.md,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '800',
    color: THEME.colors.textPrimary,
  },
  sectionSubtitle: {
    fontSize: 12,
    color: THEME.colors.textMuted,
    marginTop: 2,
    marginBottom: 8,
  },
  filtersScroll: {
    flexDirection: 'row',
    gap: 6,
  },
  filterChip: {
    backgroundColor: THEME.colors.surfaceSubtle,
    borderColor: THEME.colors.border,
    borderWidth: 1,
    paddingHorizontal: 12,
    paddingVertical: 5,
    borderRadius: THEME.radii.full,
  },
  filterChipActive: {
    backgroundColor: THEME.colors.promoPrimary,
    borderColor: THEME.colors.promoPrimary,
  },
  filterChipText: {
    fontSize: 12,
    fontWeight: '600',
    color: THEME.colors.textSecondary,
  },
  filterChipTextActive: {
    color: '#FFFFFF',
  },
  emptyState: {
    backgroundColor: THEME.colors.surface,
    borderColor: THEME.colors.border,
    borderWidth: 1,
    borderRadius: THEME.radii.lg,
    padding: 24,
    alignItems: 'center',
  },
  emptyIcon: {
    fontSize: 32,
    marginBottom: 8,
  },
  emptyTitle: {
    fontSize: 14,
    fontWeight: '700',
    color: THEME.colors.textPrimary,
  },
  emptySubtitle: {
    fontSize: 12,
    color: THEME.colors.textMuted,
    textAlign: 'center',
    marginTop: 4,
  },
  listScroll: {
    gap: 10,
  },
  listItem: {
    flexDirection: 'row',
    backgroundColor: THEME.colors.surface,
    borderColor: THEME.colors.border,
    borderWidth: 1,
    borderRadius: THEME.radii.md,
    padding: 12,
    marginBottom: 8,
    shadowColor: THEME.colors.shadow,
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.05,
    shadowRadius: 6,
    elevation: 2,
  },
  itemContent: {
    flex: 1,
  },
  itemTop: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 4,
  },
  storeBadge: {
    backgroundColor: THEME.colors.surfaceSubtle,
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 6,
  },
  storeBadgeText: {
    fontSize: 12,
    fontWeight: '800',
    color: THEME.colors.textPrimary,
  },
  discountTag: {
    backgroundColor: THEME.colors.kashkaWarmBg,
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 6,
  },
  discountTagText: {
    fontSize: 12,
    fontWeight: '800',
    color: THEME.colors.kashkaWarmText,
  },
  codeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 2,
  },
  codePrefix: {
    fontSize: 11,
    color: THEME.colors.textMuted,
  },
  codeValue: {
    fontSize: 12,
    fontWeight: '800',
    color: THEME.colors.promoPrimary,
    fontFamily: Platform.OS === 'ios' ? 'Courier' : 'monospace',
  },
  itemConditions: {
    fontSize: 11,
    color: THEME.colors.textSecondary,
    marginTop: 2,
  },
  itemBottom: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: 6,
  },
  itemDate: {
    fontSize: 11,
    color: THEME.colors.textMuted,
    fontWeight: '500',
  },
  inCalBadge: {
    fontSize: 10,
    fontWeight: '700',
    color: THEME.colors.active,
  },
  itemActions: {
    justifyContent: 'center',
    alignItems: 'center',
    paddingLeft: 10,
    gap: 6,
    borderLeftWidth: 1,
    borderLeftColor: THEME.colors.border,
    marginLeft: 10,
  },
  miniActionBtn: {
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: THEME.colors.surfaceSubtle,
    justifyContent: 'center',
    alignItems: 'center',
  },
  miniActionBtnActive: {
    backgroundColor: THEME.colors.activeLight,
  },
  miniActionText: {
    fontSize: 12,
  },
  deleteBtn: {
    backgroundColor: THEME.colors.surfaceSubtle,
  },
  deleteBtnText: {
    fontSize: 11,
    color: THEME.colors.textMuted,
    fontWeight: '700',
  },
});
