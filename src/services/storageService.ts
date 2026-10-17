import { ParsedPromo } from '../types/promo';
import { SAMPLE_PROMOS } from '../constants/samples';
import { PromoParser } from './promoParser';

const STORAGE_KEY = '@promokashka_saved_promos';

export class StorageService {
  private static memoryStore: ParsedPromo[] = [];

  /**
   * Load saved promos. If empty, seeds with user's initial example and samples.
   */
  public static async getPromos(): Promise<ParsedPromo[]> {
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        const stored = window.localStorage.getItem(STORAGE_KEY);
        if (stored) {
          return JSON.parse(stored);
        }
      }
    } catch {
      // Fallback to memory
    }

    if (this.memoryStore.length > 0) {
      return this.memoryStore;
    }

    // Initial seed: Parse the first two samples (including user's requested example)
    const initialSeed: ParsedPromo[] = [
      PromoParser.parse(SAMPLE_PROMOS[0].text).promo,
      PromoParser.parse(SAMPLE_PROMOS[1].text).promo,
    ];

    this.memoryStore = initialSeed;
    await this.savePromos(initialSeed);
    return initialSeed;
  }

  /**
   * Save promo collection
   */
  public static async savePromos(promos: ParsedPromo[]): Promise<void> {
    this.memoryStore = promos;
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify(promos));
      }
    } catch {
      // Ignore storage errors
    }
  }

  /**
   * Add a newly parsed promo to the beginning of the list
   */
  public static async addPromo(promo: ParsedPromo): Promise<ParsedPromo[]> {
    const list = await this.getPromos();
    // Prevent duplicate exact id
    const filtered = list.filter(p => p.id !== promo.id);
    const updated = [promo, ...filtered];
    await this.savePromos(updated);
    return updated;
  }

  /**
   * Update promo (e.g. mark as added to calendar or edit fields)
   */
  public static async updatePromo(updatedPromo: ParsedPromo): Promise<ParsedPromo[]> {
    const list = await this.getPromos();
    const updated = list.map(p => (p.id === updatedPromo.id ? updatedPromo : p));
    await this.savePromos(updated);
    return updated;
  }

  /**
   * Delete promo
   */
  public static async deletePromo(id: string): Promise<ParsedPromo[]> {
    const list = await this.getPromos();
    const updated = list.filter(p => p.id !== id);
    await this.savePromos(updated);
    return updated;
  }
}
