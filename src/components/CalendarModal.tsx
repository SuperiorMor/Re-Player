import React, { useState } from 'react';
import {
  View,
  Text,
  Modal,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  Linking,
  Platform,
  Alert,
} from 'react-native';
import * as Calendar from 'expo-calendar';
import * as FileSystem from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { ParsedPromo } from '../types/promo';
import { CalendarService, CalendarEventDetails } from '../services/calendarService';
import { THEME } from '../constants/theme';

interface CalendarModalProps {
  visible: boolean;
  promo: ParsedPromo | null;
  onClose: () => void;
  onSuccess: (promo: ParsedPromo) => void;
}

export const CalendarModal: React.FC<CalendarModalProps> = ({
  visible,
  promo,
  onClose,
  onSuccess,
}) => {
  const [isProcessing, setIsProcessing] = useState(false);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  if (!promo) return null;

  const eventDetails: CalendarEventDetails = CalendarService.buildEventDetails(promo);

  // 1. Native iOS / Android Calendar Integration
  const handleNativeCalendarAdd = async () => {
    setIsProcessing(true);
    setSuccessMessage(null);

    try {
      if (Platform.OS === 'web') {
        // Fallback to .ics download or Google Calendar on web
        handleDownloadIcs();
        return;
      }

      const { status } = await Calendar.requestCalendarPermissionsAsync();
      if (status !== 'granted') {
        Alert.alert(
          'Доступ к календарю',
          'Разрешите доступ к календарю в настройках устройства, чтобы Promokashka могла сохранять скидки.',
          [{ text: 'Понятно' }]
        );
        setIsProcessing(false);
        return;
      }

      // Find default calendar or create one
      const calendars = await Calendar.getCalendarsAsync(Calendar.EntityTypes.EVENT);
      const defaultCalendar =
        calendars.find(c => c.isPrimary) ||
        calendars.find(c => c.allowsModifications) ||
        calendars[0];

      if (!defaultCalendar) {
        throw new Error('Не найден доступный календарь на устройстве');
      }

      // Create event
      const eventId = await Calendar.createEventAsync(defaultCalendar.id, {
        title: eventDetails.title,
        notes: eventDetails.notes,
        location: eventDetails.location,
        startDate: eventDetails.startDate,
        endDate: eventDetails.endDate,
        alarms: eventDetails.alarms,
        timeZone: 'Europe/Moscow',
      });

      setSuccessMessage('✓ Событие успешно добавлено в ваш системный календарь!');
      onSuccess({
        ...promo,
        addedToCalendar: true,
        calendarEventId: eventId,
      });

      setTimeout(() => {
        onClose();
        setSuccessMessage(null);
      }, 1800);
    } catch (err: any) {
      console.warn('Native calendar error:', err);
      // Fallback to .ics export
      handleDownloadIcs();
    } finally {
      setIsProcessing(false);
    }
  };

  // 2. Universal .ics Export (Opens Apple Calendar / Google Calendar import)
  const handleDownloadIcs = async () => {
    setIsProcessing(true);
    try {
      const icsString = CalendarService.generateIcsContent(eventDetails);

      if (Platform.OS === 'web') {
        // Create browser download blob
        const blob = new Blob([icsString], { type: 'text/calendar;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.setAttribute('download', `promokashka_${promo.store.toLowerCase()}.ics`);
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);
      } else {
        // Mobile iOS / Android File export
        const fileUri = `${FileSystem.cacheDirectory}promokashka_${Date.now()}.ics`;
        await FileSystem.writeAsStringAsync(fileUri, icsString, {
          encoding: FileSystem.EncodingType.UTF8,
        });

        if (await Sharing.isAvailableAsync()) {
          await Sharing.shareAsync(fileUri, {
            mimeType: 'text/calendar',
            dialogTitle: 'Добавить в календарь',
            UTI: 'com.apple.ical.ics',
          });
        }
      }

      setSuccessMessage('✓ Файл календаря (.ics) сгенерирован и отправлен!');
      onSuccess({
        ...promo,
        addedToCalendar: true,
      });

      setTimeout(() => {
        onClose();
        setSuccessMessage(null);
      }, 1600);
    } catch (err) {
      console.error('ICS export error:', err);
      Alert.alert('Ошибка', 'Не удалось экспортировать файл календаря.');
    } finally {
      setIsProcessing(false);
    }
  };

  // 3. Open in Google Calendar
  const handleOpenGoogleCalendar = async () => {
    try {
      const url = CalendarService.generateGoogleCalendarUrl(eventDetails);
      await Linking.openURL(url);
      onSuccess({
        ...promo,
        addedToCalendar: true,
      });
      onClose();
    } catch {
      Alert.alert('Ошибка', 'Не удалось открыть Google Календарь');
    }
  };

  return (
    <Modal
      visible={visible}
      transparent
      animationType="slide"
      onRequestClose={onClose}
    >
      <View style={styles.modalOverlay}>
        <View style={styles.modalContainer}>
          {/* Header */}
          <View style={styles.header}>
            <View>
              <Text style={styles.title}>Поставить в календарь</Text>
              <Text style={styles.subtitle}>
                Promokashka напомнит воспользоваться скидкой
              </Text>
            </View>
            <TouchableOpacity
              style={styles.closeBtn}
              onPress={onClose}
              activeOpacity={0.7}
            >
              <Text style={styles.closeBtnText}>✕</Text>
            </TouchableOpacity>
          </View>

          {/* Success Banner */}
          {successMessage && (
            <View style={styles.successBanner}>
              <Text style={styles.successBannerText}>{successMessage}</Text>
            </View>
          )}

          {/* Event Preview Card */}
          <ScrollView style={styles.previewBox}>
            <View style={styles.previewHeader}>
              <Text style={styles.previewBadge}>ПРЕДПРОСМОТР СОБЫТИЯ</Text>
            </View>

            <Text style={styles.previewTitle}>{eventDetails.title}</Text>

            <View style={styles.previewMeta}>
              <Text style={styles.metaRow}>
                📍 Место: {eventDetails.location}
              </Text>
              <Text style={styles.metaRow}>
                ⏰ Дата: {promo.expirationDateDisplay}
              </Text>
              <Text style={styles.metaRow}>
                🔔 Напоминания: За 24 часа и за 3 часа
              </Text>
            </View>

            <View style={styles.notesDivider} />
            <Text style={styles.notesPreview} numberOfLines={5}>
              {eventDetails.notes}
            </Text>
          </ScrollView>

          {/* Action Options */}
          <View style={styles.actionsContainer}>
            {/* Primary Action */}
            <TouchableOpacity
              style={[
                styles.primaryBtn,
                isProcessing && styles.primaryBtnDisabled,
              ]}
              onPress={handleNativeCalendarAdd}
              disabled={isProcessing}
              activeOpacity={0.8}
            >
              <Text style={styles.primaryBtnText}>
                {isProcessing
                  ? 'Сохраняем...'
                  : Platform.OS === 'web'
                  ? '📅 Скачать .ICS для Календаря'
                  : '📲 Добавить в системный Календарь'}
              </Text>
            </TouchableOpacity>

            {/* Google Calendar Link */}
            <TouchableOpacity
              style={styles.secondaryBtn}
              onPress={handleOpenGoogleCalendar}
              activeOpacity={0.7}
            >
              <Text style={styles.secondaryBtnText}>
                🌐 Открыть в Google Календаре
              </Text>
            </TouchableOpacity>

            {/* Export .ICS format */}
            {Platform.OS !== 'web' && (
              <TouchableOpacity
                style={styles.tertiaryBtn}
                onPress={handleDownloadIcs}
                activeOpacity={0.7}
              >
                <Text style={styles.tertiaryBtnText}>
                  📁 Поделиться .ICS файлом
                </Text>
              </TouchableOpacity>
            )}
          </View>
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(15, 23, 42, 0.65)',
    justifyContent: 'flex-end',
  },
  modalContainer: {
    backgroundColor: THEME.colors.surface,
    borderTopLeftRadius: THEME.radii.xl,
    borderTopRightRadius: THEME.radii.xl,
    padding: THEME.spacing.lg,
    maxHeight: '85%',
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: THEME.spacing.md,
  },
  title: {
    fontSize: 18,
    fontWeight: '800',
    color: THEME.colors.textPrimary,
  },
  subtitle: {
    fontSize: 12,
    color: THEME.colors.textMuted,
    marginTop: 2,
  },
  closeBtn: {
    width: 32,
    height: 32,
    borderRadius: 16,
    backgroundColor: THEME.colors.surfaceSubtle,
    justifyContent: 'center',
    alignItems: 'center',
  },
  closeBtnText: {
    fontSize: 14,
    color: THEME.colors.textSecondary,
    fontWeight: '700',
  },
  successBanner: {
    backgroundColor: THEME.colors.activeLight,
    borderColor: THEME.colors.activeBorder,
    borderWidth: 1,
    padding: 12,
    borderRadius: THEME.radii.md,
    marginBottom: 12,
  },
  successBannerText: {
    color: THEME.colors.active,
    fontSize: 13,
    fontWeight: '700',
    textAlign: 'center',
  },
  previewBox: {
    backgroundColor: THEME.colors.background,
    borderColor: THEME.colors.border,
    borderWidth: 1,
    borderRadius: THEME.radii.md,
    padding: THEME.spacing.md,
    maxHeight: 220,
    marginBottom: THEME.spacing.lg,
  },
  previewHeader: {
    marginBottom: 6,
  },
  previewBadge: {
    fontSize: 10,
    fontWeight: '800',
    color: THEME.colors.promoPrimary,
    letterSpacing: 0.5,
  },
  previewTitle: {
    fontSize: 15,
    fontWeight: '800',
    color: THEME.colors.textPrimary,
    marginBottom: 8,
  },
  previewMeta: {
    gap: 4,
  },
  metaRow: {
    fontSize: 12,
    color: THEME.colors.textSecondary,
    fontWeight: '500',
  },
  notesDivider: {
    height: 1,
    backgroundColor: THEME.colors.border,
    marginVertical: 8,
  },
  notesPreview: {
    fontSize: 11,
    color: THEME.colors.textMuted,
    fontFamily: Platform.OS === 'ios' ? 'Courier' : 'monospace',
    lineHeight: 16,
  },
  actionsContainer: {
    gap: 10,
    marginBottom: Platform.OS === 'ios' ? 20 : 10,
  },
  primaryBtn: {
    backgroundColor: THEME.colors.promoPrimary,
    borderRadius: THEME.radii.md,
    paddingVertical: 14,
    alignItems: 'center',
    shadowColor: THEME.colors.promoPrimary,
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.25,
    shadowRadius: 8,
    elevation: 3,
  },
  primaryBtnDisabled: {
    backgroundColor: THEME.colors.border,
  },
  primaryBtnText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '800',
  },
  secondaryBtn: {
    backgroundColor: THEME.colors.surfaceSubtle,
    borderColor: THEME.colors.border,
    borderWidth: 1,
    borderRadius: THEME.radii.md,
    paddingVertical: 12,
    alignItems: 'center',
  },
  secondaryBtnText: {
    color: THEME.colors.textPrimary,
    fontSize: 14,
    fontWeight: '600',
  },
  tertiaryBtn: {
    paddingVertical: 8,
    alignItems: 'center',
  },
  tertiaryBtnText: {
    color: THEME.colors.textMuted,
    fontSize: 12,
    fontWeight: '600',
  },
});
