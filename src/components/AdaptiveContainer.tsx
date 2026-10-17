import React, { useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  useWindowDimensions,
  SafeAreaView,
  Platform,
  TouchableOpacity,
  StatusBar,
} from 'react-native';
import { THEME } from '../constants/theme';

interface AdaptiveContainerProps {
  children: React.ReactNode;
}

export type SimulatedDevice = 'responsive' | 'iphone-se' | 'iphone-15-pro' | 'pixel-8';

export const AdaptiveContainer: React.FC<AdaptiveContainerProps> = ({ children }) => {
  const { width: windowWidth } = useWindowDimensions();
  const [selectedDevice, setSelectedDevice] = useState<SimulatedDevice>('responsive');

  // Device width presets
  const deviceSpecs = {
    'iphone-se': { width: 375, name: 'iPhone SE (375px)', platform: 'iOS' },
    'iphone-15-pro': { width: 393, name: 'iPhone 15 Pro (393px)', platform: 'iOS' },
    'pixel-8': { width: 412, name: 'Pixel 8 / Galaxy (412px)', platform: 'Android' },
    responsive: { width: Math.min(windowWidth, 540), name: 'Адаптивный', platform: 'Auto' },
  };

  const isWebOrDesktop = Platform.OS === 'web' && windowWidth > 600;
  const targetWidth = isWebOrDesktop ? deviceSpecs[selectedDevice].width : '100%';

  return (
    <SafeAreaView style={styles.outerContainer}>
      <StatusBar barStyle="dark-content" backgroundColor="#FFFFFF" />

      {/* Device Resolution Switcher (Only visible in web/desktop preview for testing) */}
      {isWebOrDesktop && (
        <View style={styles.deviceToolbar}>
          <Text style={styles.deviceToolbarLabel}>📱 Разрешение экрана:</Text>
          <View style={styles.deviceButtonsRow}>
            {(['iphone-se', 'iphone-15-pro', 'pixel-8', 'responsive'] as SimulatedDevice[]).map(dev => (
              <TouchableOpacity
                key={dev}
                style={[
                  styles.deviceButton,
                  selectedDevice === dev && styles.deviceButtonActive,
                ]}
                onPress={() => setSelectedDevice(dev)}
              >
                <Text
                  style={[
                    styles.deviceButtonText,
                    selectedDevice === dev && styles.deviceButtonTextActive,
                  ]}
                >
                  {deviceSpecs[dev].name}
                </Text>
              </TouchableOpacity>
            ))}
          </View>
        </View>
      )}

      {/* Main Responsive Mobile Frame */}
      <View
        style={[
          styles.innerFrame,
          isWebOrDesktop && {
            width: targetWidth,
            borderWidth: 1,
            borderColor: THEME.colors.border,
            borderRadius: 36,
            overflow: 'hidden',
            shadowColor: '#000000',
            shadowOffset: { width: 0, height: 12 },
            shadowOpacity: 0.12,
            shadowRadius: 24,
            marginVertical: 16,
          },
        ]}
      >
        {children}
      </View>
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  outerContainer: {
    flex: 1,
    backgroundColor: '#EEF2F6',
    alignItems: 'center',
    justifyContent: 'flex-start',
  },
  deviceToolbar: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#FFFFFF',
    paddingVertical: 10,
    paddingHorizontal: 16,
    borderRadius: 30,
    marginTop: 12,
    marginBottom: 4,
    borderWidth: 1,
    borderColor: THEME.colors.border,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.05,
    shadowRadius: 6,
    gap: 12,
  },
  deviceToolbarLabel: {
    fontSize: 12,
    fontWeight: '700',
    color: THEME.colors.textSecondary,
  },
  deviceButtonsRow: {
    flexDirection: 'row',
    gap: 6,
  },
  deviceButton: {
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 16,
    backgroundColor: THEME.colors.surfaceSubtle,
  },
  deviceButtonActive: {
    backgroundColor: THEME.colors.promoPrimary,
  },
  deviceButtonText: {
    fontSize: 11,
    fontWeight: '600',
    color: THEME.colors.textSecondary,
  },
  deviceButtonTextActive: {
    color: '#FFFFFF',
  },
  innerFrame: {
    flex: 1,
    width: '100%',
    maxWidth: 540,
    backgroundColor: THEME.colors.background,
  },
});
