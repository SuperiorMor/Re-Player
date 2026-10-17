import React, { useState, useEffect } from 'react';
import {
  View,
  ScrollView,
  StyleSheet,
  Alert,
  Modal,
  Text,
  TouchableOpacity,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { Header } from './src/components/Header';
import { PromoInput } from './src/components/PromoInput';
import { ExtractedPromoCard } from './src/components/ExtractedPromoCard';
import { CalendarModal } from './src/components/CalendarModal';
import { EditPromoModal } from './src/components/EditPromoModal';
import { HistoryList } from './src/components/HistoryList';
import { AdaptiveContainer } from './src/components/AdaptiveContainer';
import { PromoParser } from './src/services/promoParser';
import { StorageService } from './src/services/storageService';
import { ParsedPromo } from './src/types/promo';
import { THEME } from './src/constants/theme';
import { SAMPLE_PROMOS } from './src/constants/samples';

export default function App() {
  const [currentPromo, setCurrentPromo] = useState<ParsedPromo | null>(null);
  const [savedPromos, setSavedPromos] = useState<ParsedPromo[]>([]);
  const [isCalendarModalVisible, setIsCalendarModalVisible] = useState(false);
  const [isEditModalVisible, setIsEditModalVisible] = useState(false);
  const [isHelpVisible, setIsHelpVisible] = useState(false);
  const [selectedPromoForCalendar, setSelectedPromoForCalendar] = useState<ParsedPromo | null>(null);

  // Initialize with initial samples
  useEffect(() => {
    async function loadData() {
      const data = await StorageService.getPromos();
      setSavedPromos(data);

      // Analyze user's primary sample as default view
      const initialParsed = PromoParser.parse(SAMPLE_PROMOS[0].text);
      setCurrentPromo(initialParsed.promo);
    }
    loadData();
  }, []);

  // Handle NLP Analysis of user input text
  const handleAnalyzeText = (text: string) => {
    const parseResult = PromoParser.parse(text);
    setCurrentPromo(parseResult.promo);
  };

  // Open calendar sync modal
  const handleOpenCalendar = (promo: ParsedPromo) => {
    setSelectedPromoForCalendar(promo);
    setIsCalendarModalVisible(true);
  };

  // When promo is successfully added to calendar
  const handleCalendarSuccess = async (updatedPromo: ParsedPromo) => {
    if (currentPromo?.id === updatedPromo.id) {
      setCurrentPromo(updatedPromo);
    }
    const updatedList = await StorageService.updatePromo(updatedPromo);
    setSavedPromos(updatedList);
  };

  // Open edit modal
  const handleOpenEdit = (promo: ParsedPromo) => {
    setCurrentPromo(promo);
    setIsEditModalVisible(true);
  };

  // Save edited promo
  const handleSaveEditedPromo = async (updatedPromo: ParsedPromo) => {
    setCurrentPromo(updatedPromo);
    const updatedList = await StorageService.updatePromo(updatedPromo);
    setSavedPromos(updatedList);
  };

  // Save to persistent list
  const handleSavePromo = async (promo: ParsedPromo) => {
    const updatedList = await StorageService.addPromo(promo);
    setSavedPromos(updatedList);
    Alert.alert('Успешно', 'Акция сохранена в ваш список');
  };

  // Delete promo
  const handleDeletePromo = async (id: string) => {
    const updatedList = await StorageService.deletePromo(id);
    setSavedPromos(updatedList);
    if (currentPromo?.id === id) {
      setCurrentPromo(null);
    }
  };

  const activeCount = savedPromos.filter(p => !p.isExpired).length;
  const expiringCount = savedPromos.filter(
    p => !p.isExpired && p.daysRemaining !== null && p.daysRemaining <= 3
  ).length;

  const isCurrentPromoSaved = savedPromos.some(p => p.id === currentPromo?.id);

  return (
    <AdaptiveContainer>
      <StatusBar style="dark" />
      <KeyboardAvoidingView
        style={styles.keyboardContainer}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        {/* Brand Header with Logo interplay "Promo" + "kashka" */}
        <Header
          activeCount={activeCount}
          expiringCount={expiringCount}
          onHelpPress={() => setIsHelpVisible(true)}
        />

        <ScrollView
          style={styles.scrollArea}
          contentContainerStyle={styles.scrollContent}
          keyboardShouldPersistTaps="handled"
          showsVerticalScrollIndicator={false}
        >
          {/* Main Promo Text Input Card */}
          <PromoInput onAnalyze={handleAnalyzeText} />

          {/* Extracted Entity Card */}
          {currentPromo && (
            <View style={styles.sectionWrapper}>
              <View style={styles.sectionHeaderRow}>
                <Text style={styles.sectionBadge}>РЕЗУЛЬТАТ РАСПОЗНАВАНИЯ</Text>
                <Text style={styles.sectionHint}>Нажмите на карточку для деталей</Text>
              </View>

              <ExtractedPromoCard
                promo={currentPromo}
                onAddToCalendar={handleOpenCalendar}
                onEdit={handleOpenEdit}
                onSave={handleSavePromo}
                isSaved={isCurrentPromoSaved}
              />
            </View>
          )}

          {/* Saved Promos History */}
          <HistoryList
            promos={savedPromos}
            onSelectPromo={setCurrentPromo}
            onAddToCalendar={handleOpenCalendar}
            onDeletePromo={handleDeletePromo}
          />
        </ScrollView>
      </KeyboardAvoidingView>

      {/* Calendar Add Modal */}
      <CalendarModal
        visible={isCalendarModalVisible}
        promo={selectedPromoForCalendar}
        onClose={() => setIsCalendarModalVisible(false)}
        onSuccess={handleCalendarSuccess}
      />

      {/* Edit Promo Fields Modal */}
      <EditPromoModal
        visible={isEditModalVisible}
        promo={currentPromo}
        onClose={() => setIsEditModalVisible(false)}
        onSave={handleSaveEditedPromo}
      />

      {/* Help / Brand Story Modal */}
      <Modal
        visible={isHelpVisible}
        transparent
        animationType="fade"
        onRequestClose={() => setIsHelpVisible(false)}
      >
        <View style={styles.helpOverlay}>
          <View style={styles.helpCard}>
            <View style={styles.helpHeader}>
              <View style={styles.helpLogoBadge}>
                <Text style={styles.helpLogoText}>Promo</Text>
                <Text style={styles.helpLogoSub}>kashka</Text>
              </View>
              <TouchableOpacity
                onPress={() => setIsHelpVisible(false)}
                style={styles.helpClose}
              >
                <Text style={styles.helpCloseText}>✕</Text>
              </TouchableOpacity>
            </View>

            <Text style={styles.helpTitle}>О концепции Promokashka</Text>

            <Text style={styles.helpParagraph}>
              <Text style={{ fontWeight: '700' }}>Promokashka (Промокашка)</Text> обыгрывает две части:
            </Text>
            <View style={styles.helpPoints}>
              <Text style={styles.helpPointItem}>
                🏷️ <Text style={{ fontWeight: '700' }}>Promo</Text> — мгновенно находит скидки, промокоды, условия и даты в любом тексте.
              </Text>
              <Text style={styles.helpPointItem}>
                💧 <Text style={{ fontWeight: '700' }}>kashka</Text> — как промокашка впитывает чернила, так приложение впитывает скидки до последней капли и ставит напоминание в календарь, чтобы вы успели их потратить.
              </Text>
            </View>

            <View style={styles.helpSupported}>
              <Text style={styles.helpSupportedTitle}>Поддерживаемые платформы:</Text>
              <Text style={styles.helpSupportedText}>
                • iOS (Apple Calendar, Reminders, Expo Go)
              </Text>
              <Text style={styles.helpSupportedText}>
                • Android (Google Calendar, Samsung Calendar)
              </Text>
              <Text style={styles.helpSupportedText}>
                • Universal .ICS экспорт для всех устройств
              </Text>
            </View>

            <TouchableOpacity
              style={styles.helpDoneBtn}
              onPress={() => setIsHelpVisible(false)}
            >
              <Text style={styles.helpDoneText}>Понятно</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>
    </AdaptiveContainer>
  );
}

const styles = StyleSheet.create({
  keyboardContainer: {
    flex: 1,
  },
  scrollArea: {
    flex: 1,
  },
  scrollContent: {
    paddingBottom: 40,
  },
  sectionWrapper: {
    marginTop: THEME.spacing.md,
  },
  sectionHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: THEME.spacing.lg,
    marginTop: 8,
    marginBottom: 2,
  },
  sectionBadge: {
    fontSize: 11,
    fontWeight: '800',
    color: THEME.colors.promoPrimary,
    letterSpacing: 0.6,
  },
  sectionHint: {
    fontSize: 11,
    color: THEME.colors.textMuted,
  },
  helpOverlay: {
    flex: 1,
    backgroundColor: 'rgba(15, 23, 42, 0.65)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  helpCard: {
    backgroundColor: '#FFFFFF',
    borderRadius: 24,
    padding: 24,
    width: '100%',
    maxWidth: 420,
  },
  helpHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 16,
  },
  helpLogoBadge: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  helpLogoText: {
    fontSize: 20,
    fontWeight: '900',
    color: THEME.colors.promoPrimary,
  },
  helpLogoSub: {
    fontSize: 20,
    fontWeight: '700',
    color: THEME.colors.kashkaAccent,
    fontStyle: 'italic',
  },
  helpClose: {
    width: 32,
    height: 32,
    borderRadius: 16,
    backgroundColor: THEME.colors.surfaceSubtle,
    justifyContent: 'center',
    alignItems: 'center',
  },
  helpCloseText: {
    fontSize: 14,
    fontWeight: '700',
    color: THEME.colors.textSecondary,
  },
  helpTitle: {
    fontSize: 17,
    fontWeight: '800',
    color: THEME.colors.textPrimary,
    marginBottom: 8,
  },
  helpParagraph: {
    fontSize: 13,
    color: THEME.colors.textSecondary,
    lineHeight: 20,
    marginBottom: 8,
  },
  helpPoints: {
    gap: 8,
    marginBottom: 16,
  },
  helpPointItem: {
    fontSize: 13,
    color: THEME.colors.textSecondary,
    lineHeight: 18,
  },
  helpSupported: {
    backgroundColor: THEME.colors.surfaceSubtle,
    borderRadius: 12,
    padding: 12,
    marginBottom: 16,
  },
  helpSupportedTitle: {
    fontSize: 12,
    fontWeight: '700',
    color: THEME.colors.textPrimary,
    marginBottom: 4,
  },
  helpSupportedText: {
    fontSize: 12,
    color: THEME.colors.textSecondary,
    lineHeight: 18,
  },
  helpDoneBtn: {
    backgroundColor: THEME.colors.promoPrimary,
    borderRadius: 12,
    paddingVertical: 12,
    alignItems: 'center',
  },
  helpDoneText: {
    color: '#FFFFFF',
    fontSize: 14,
    fontWeight: '700',
  },
});
