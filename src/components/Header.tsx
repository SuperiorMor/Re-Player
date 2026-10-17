import React from 'react';
import { View, Text, StyleSheet, TouchableOpacity } from 'react-native';
import { THEME } from '../constants/theme';

interface HeaderProps {
  activeCount?: number;
  expiringCount?: number;
  onHelpPress?: () => void;
}

export const Header: React.FC<HeaderProps> = ({
  activeCount = 0,
  expiringCount = 0,
  onHelpPress,
}) => {
  return (
    <View style={styles.container}>
      {/* Top Brand Bar */}
      <View style={styles.brandRow}>
        <View style={styles.logoWrapper}>
          {/* Stylized Blotter Stamp / Ticket Icon */}
          <View style={styles.iconStamp}>
            <View style={styles.iconTicketCutoutTop} />
            <Text style={styles.iconPercent}>%</Text>
            <View style={styles.iconInkDot} />
            <View style={styles.iconTicketCutoutBottom} />
          </View>

          {/* Logo Name: Interplay between "Promo" and "kashka" */}
          <View style={styles.textContainer}>
            <View style={styles.titleRow}>
              {/* Promo: Bold, modern, accented pill badge style */}
              <View style={styles.promoBadge}>
                <Text style={styles.promoText}>Promo</Text>
              </View>

              {/* kashka: Warm, rounded, lowercase cursive style with ink motif */}
              <View style={styles.kashkaWrapper}>
                <Text style={styles.kashkaText}>kashka</Text>
                <View style={styles.kashkaDot} />
              </View>
            </View>

            <Text style={styles.tagline}>
              Впитывает скидки • Держит в календаре
            </Text>
          </View>
        </View>

        {onHelpPress && (
          <TouchableOpacity
            style={styles.helpButton}
            onPress={onHelpPress}
            activeOpacity={0.7}
            accessibilityLabel="О приложении"
          >
            <Text style={styles.helpButtonText}>?</Text>
          </TouchableOpacity>
        )}
      </View>

      {/* Quick stats indicator */}
      {(activeCount > 0 || expiringCount > 0) && (
        <View style={styles.statsRow}>
          <View style={styles.statPillActive}>
            <View style={styles.statDotActive} />
            <Text style={styles.statTextActive}>
              {activeCount} {getNoun(activeCount, 'акция', 'акции', 'акций')}
            </Text>
          </View>

          {expiringCount > 0 && (
            <View style={styles.statPillExpiring}>
              <View style={styles.statDotExpiring} />
              <Text style={styles.statTextExpiring}>
                {expiringCount} {getNoun(expiringCount, 'истекает', 'истекают', 'истекают')}
              </Text>
            </View>
          )}
        </View>
      )}
    </View>
  );
};

function getNoun(number: number, one: string, two: string, five: string) {
  let n = Math.abs(number);
  n %= 100;
  if (n >= 5 && n <= 20) return five;
  n %= 10;
  if (n === 1) return one;
  if (n >= 2 && n <= 4) return two;
  return five;
}

const styles = StyleSheet.create({
  container: {
    paddingHorizontal: THEME.spacing.lg,
    paddingTop: THEME.spacing.md,
    paddingBottom: THEME.spacing.md,
    backgroundColor: THEME.colors.surface,
    borderBottomWidth: 1,
    borderBottomColor: THEME.colors.border,
  },
  brandRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  logoWrapper: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  iconStamp: {
    width: 42,
    height: 42,
    borderRadius: 12,
    backgroundColor: THEME.colors.promoPrimary,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
    position: 'relative',
    shadowColor: THEME.colors.promoPrimary,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.25,
    shadowRadius: 8,
    elevation: 4,
  },
  iconPercent: {
    color: '#FFFFFF',
    fontSize: 20,
    fontWeight: '900',
    letterSpacing: -1,
  },
  iconInkDot: {
    position: 'absolute',
    top: 5,
    right: 5,
    width: 7,
    height: 7,
    borderRadius: 3.5,
    backgroundColor: THEME.colors.kashkaAccent,
  },
  iconTicketCutoutTop: {
    position: 'absolute',
    left: -4,
    top: 17,
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: THEME.colors.surface,
  },
  iconTicketCutoutBottom: {
    position: 'absolute',
    right: -4,
    top: 17,
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: THEME.colors.surface,
  },
  textContainer: {
    justifyContent: 'center',
  },
  titleRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  promoBadge: {
    backgroundColor: THEME.colors.promoPrimary,
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 8,
    marginRight: 4,
  },
  promoText: {
    color: '#FFFFFF',
    fontSize: 20,
    fontWeight: '900',
    letterSpacing: 0.5,
    textTransform: 'uppercase',
  },
  kashkaWrapper: {
    flexDirection: 'row',
    alignItems: 'baseline',
    position: 'relative',
  },
  kashkaText: {
    color: THEME.colors.kashkaAccent,
    fontSize: 22,
    fontWeight: '700',
    letterSpacing: -0.5,
    fontStyle: 'italic',
  },
  kashkaDot: {
    width: 5,
    height: 5,
    borderRadius: 2.5,
    backgroundColor: THEME.colors.kashkaAccent,
    marginLeft: 2,
    marginBottom: 4,
  },
  tagline: {
    fontSize: 11,
    color: THEME.colors.textMuted,
    fontWeight: '500',
    marginTop: 2,
  },
  helpButton: {
    width: 32,
    height: 32,
    borderRadius: 16,
    backgroundColor: THEME.colors.surfaceSubtle,
    borderWidth: 1,
    borderColor: THEME.colors.border,
    justifyContent: 'center',
    alignItems: 'center',
  },
  helpButtonText: {
    color: THEME.colors.textSecondary,
    fontSize: 15,
    fontWeight: '700',
  },
  statsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 10,
    gap: 8,
  },
  statPillActive: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: THEME.colors.activeLight,
    borderColor: THEME.colors.activeBorder,
    borderWidth: 1,
    paddingHorizontal: 10,
    paddingVertical: 3,
    borderRadius: 9999,
  },
  statDotActive: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: THEME.colors.active,
    marginRight: 6,
  },
  statTextActive: {
    fontSize: 12,
    color: THEME.colors.active,
    fontWeight: '600',
  },
  statPillExpiring: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: THEME.colors.expiringLight,
    borderColor: THEME.colors.expiringBorder,
    borderWidth: 1,
    paddingHorizontal: 10,
    paddingVertical: 3,
    borderRadius: 9999,
  },
  statDotExpiring: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: THEME.colors.expiring,
    marginRight: 6,
  },
  statTextExpiring: {
    fontSize: 12,
    color: THEME.colors.expiring,
    fontWeight: '600',
  },
});
