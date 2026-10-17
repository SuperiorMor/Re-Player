import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  Modal,
  StyleSheet,
  TextInput,
  TouchableOpacity,
  ScrollView,
  Platform,
} from 'react-native';
import { ParsedPromo } from '../types/promo';
import { THEME } from '../constants/theme';

interface EditPromoModalProps {
  visible: boolean;
  promo: ParsedPromo | null;
  onClose: () => void;
  onSave: (updatedPromo: ParsedPromo) => void;
}

export const EditPromoModal: React.FC<EditPromoModalProps> = ({
  visible,
  promo,
  onClose,
  onSave,
}) => {
  const [store, setStore] = useState('');
  const [discount, setDiscount] = useState('');
  const [promoCode, setPromoCode] = useState('');
  const [expirationDisplay, setExpirationDisplay] = useState('');
  const [conditions, setConditions] = useState('');

  useEffect(() => {
    if (promo) {
      setStore(promo.store);
      setDiscount(promo.discount);
      setPromoCode(promo.promoCode || '');
      setExpirationDisplay(promo.expirationDateDisplay);
      setConditions(promo.conditions);
    }
  }, [promo]);

  if (!promo) return null;

  const handleSave = () => {
    const updated: ParsedPromo = {
      ...promo,
      store: store.trim() || 'Магазин',
      discount: discount.trim() || 'Скидка',
      promoCode: promoCode.trim() ? promoCode.trim().toUpperCase() : null,
      expirationDateDisplay: expirationDisplay.trim() || promo.expirationDateDisplay,
      conditions: conditions.trim() || 'Без ограничений',
    };
    onSave(updated);
    onClose();
  };

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      onRequestClose={onClose}
    >
      <View style={styles.modalOverlay}>
        <View style={styles.modalContent}>
          <View style={styles.header}>
            <Text style={styles.title}>Редактировать параметры</Text>
            <TouchableOpacity onPress={onClose} style={styles.closeBtn}>
              <Text style={styles.closeBtnText}>✕</Text>
            </TouchableOpacity>
          </View>

          <ScrollView style={styles.formScroll}>
            {/* Store */}
            <View style={styles.fieldGroup}>
              <Text style={styles.label}>Магазин или сервис</Text>
              <TextInput
                style={styles.input}
                value={store}
                onChangeText={setStore}
                placeholder="Например: Магнит"
              />
            </View>

            {/* Discount */}
            <View style={styles.fieldGroup}>
              <Text style={styles.label}>Размер скидки</Text>
              <TextInput
                style={styles.input}
                value={discount}
                onChangeText={setDiscount}
                placeholder="Например: -30%"
              />
            </View>

            {/* Promo Code */}
            <View style={styles.fieldGroup}>
              <Text style={styles.label}>Промокод (если есть)</Text>
              <TextInput
                style={[styles.input, styles.monoInput]}
                value={promoCode}
                onChangeText={setPromoCode}
                placeholder="Например: MAGNIT30"
                autoCapitalize="characters"
              />
            </View>

            {/* Expiration date */}
            <View style={styles.fieldGroup}>
              <Text style={styles.label}>Дата окончания</Text>
              <TextInput
                style={styles.input}
                value={expirationDisplay}
                onChangeText={setExpirationDisplay}
                placeholder="Например: 21 октября 2026"
              />
            </View>

            {/* Conditions */}
            <View style={styles.fieldGroup}>
              <Text style={styles.label}>Условия использования</Text>
              <TextInput
                style={[styles.input, styles.multilineInput]}
                value={conditions}
                onChangeText={setConditions}
                multiline
                numberOfLines={2}
                placeholder="Например: на доставку от 1800 рублей"
              />
            </View>
          </ScrollView>

          {/* Action buttons */}
          <View style={styles.footerRow}>
            <TouchableOpacity
              style={styles.cancelButton}
              onPress={onClose}
              activeOpacity={0.7}
            >
              <Text style={styles.cancelButtonText}>Отмена</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.saveButton}
              onPress={handleSave}
              activeOpacity={0.8}
            >
              <Text style={styles.saveButtonText}>Сохранить</Text>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(15, 23, 42, 0.6)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: THEME.spacing.lg,
  },
  modalContent: {
    backgroundColor: THEME.colors.surface,
    borderRadius: THEME.radii.lg,
    padding: THEME.spacing.lg,
    width: '100%',
    maxWidth: 420,
    maxHeight: '85%',
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: THEME.spacing.md,
  },
  title: {
    fontSize: 17,
    fontWeight: '800',
    color: THEME.colors.textPrimary,
  },
  closeBtn: {
    width: 30,
    height: 30,
    borderRadius: 15,
    backgroundColor: THEME.colors.surfaceSubtle,
    justifyContent: 'center',
    alignItems: 'center',
  },
  closeBtnText: {
    fontSize: 14,
    color: THEME.colors.textSecondary,
    fontWeight: '700',
  },
  formScroll: {
    marginBottom: THEME.spacing.md,
  },
  fieldGroup: {
    marginBottom: THEME.spacing.sm,
  },
  label: {
    fontSize: 12,
    fontWeight: '600',
    color: THEME.colors.textSecondary,
    marginBottom: 4,
  },
  input: {
    backgroundColor: THEME.colors.background,
    borderColor: THEME.colors.border,
    borderWidth: 1,
    borderRadius: THEME.radii.sm,
    paddingHorizontal: 12,
    paddingVertical: 9,
    fontSize: 14,
    color: THEME.colors.textPrimary,
  },
  monoInput: {
    fontFamily: Platform.OS === 'ios' ? 'Courier' : 'monospace',
    fontWeight: '700',
  },
  multilineInput: {
    minHeight: 55,
    textAlignVertical: 'top',
  },
  footerRow: {
    flexDirection: 'row',
    gap: 10,
  },
  cancelButton: {
    flex: 1,
    backgroundColor: THEME.colors.surfaceSubtle,
    borderRadius: THEME.radii.md,
    paddingVertical: 12,
    alignItems: 'center',
  },
  cancelButtonText: {
    color: THEME.colors.textSecondary,
    fontSize: 14,
    fontWeight: '600',
  },
  saveButton: {
    flex: 1.5,
    backgroundColor: THEME.colors.promoPrimary,
    borderRadius: THEME.radii.md,
    paddingVertical: 12,
    alignItems: 'center',
  },
  saveButtonText: {
    color: '#FFFFFF',
    fontSize: 14,
    fontWeight: '700',
  },
});
