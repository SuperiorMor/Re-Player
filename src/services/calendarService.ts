import { ParsedPromo } from '../types/promo';

export interface CalendarEventDetails {
  title: string;
  notes: string;
  location: string;
  startDate: Date;
  endDate: Date;
  alarms: { relativeOffset: number }[]; // minutes before event (e.g. -1440 for 1 day, -180 for 3 hours)
}

export class CalendarService {
  /**
   * Format promo details into a calendar event specification
   */
  public static buildEventDetails(promo: ParsedPromo): CalendarEventDetails {
    const codeText = promo.promoCode ? `[Промокод: ${promo.promoCode}]` : '[Без промокода]';
    const title = `🏷️ ${promo.store}: ${promo.discount} ${codeText}`;

    // Target expiration date
    let endDate = promo.expirationDate ? new Date(promo.expirationDate) : new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
    if (isNaN(endDate.getTime())) {
      endDate = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
    }

    // Default start date: beginning of the expiration day or 2 hours before end
    const startDate = new Date(endDate.getTime() - 2 * 60 * 60 * 1000);

    const notes = [
      `🔥 АКЦИЯ ОТ PROMOKASHKA 🔥`,
      `━━━━━━━━━━━━━━━━━━━━━`,
      `🏪 Магазин / Сервис: ${promo.store}`,
      `💰 Размер скидки: ${promo.discount}`,
      `🎟️ Промокод: ${promo.promoCode || 'Не требуется (автоскидка в приложении/на кассе)'}`,
      `📋 Условия: ${promo.conditions}`,
      `⏳ Действительно до: ${promo.expirationDateDisplay}`,
      `━━━━━━━━━━━━━━━━━━━━━`,
      `📝 Исходное описание:`,
      `"${promo.originalText}"`,
      `\nСохранено в приложении Promokashka`,
    ].join('\n');

    return {
      title,
      notes,
      location: `${promo.store} (Приложение / Доставка / Магазин)`,
      startDate,
      endDate,
      alarms: [
        { relativeOffset: -1440 }, // 1 day before
        { relativeOffset: -180 },  // 3 hours before
      ],
    };
  }

  /**
   * Generate RFC 5545 compliant iCalendar (.ics) string
   * This is universally compatible with iOS Calendar, Android Google Calendar, Outlook, macOS
   */
  public static generateIcsContent(event: CalendarEventDetails): string {
    const formatDate = (date: Date) => {
      return date
        .toISOString()
        .replace(/[-:]/g, '')
        .replace(/\.\d{3}/, '');
    };

    const escapeText = (str: string) => {
      return str
        .replace(/\\/g, '\\\\')
        .replace(/;/g, '\\;')
        .replace(/,/g, '\\,')
        .replace(/\n/g, '\\n');
    };

    const uid = 'promokashka_' + Date.now() + '@promokashka.app';
    const stamp = formatDate(new Date());
    const dtStart = formatDate(event.startDate);
    const dtEnd = formatDate(event.endDate);

    return [
      'BEGIN:VCALENDAR',
      'VERSION:2.0',
      'PRODID:-//Promokashka//Promokashka Mobile App//RU',
      'CALSCALE:GREGORIAN',
      'METHOD:PUBLISH',
      'BEGIN:VEVENT',
      `UID:${uid}`,
      `DTSTAMP:${stamp}`,
      `DTSTART:${dtStart}`,
      `DTEND:${dtEnd}`,
      `SUMMARY:${escapeText(event.title)}`,
      `DESCRIPTION:${escapeText(event.notes)}`,
      `LOCATION:${escapeText(event.location)}`,
      'STATUS:CONFIRMED',
      // Alarm 1: 1 day before
      'BEGIN:VALARM',
      'ACTION:DISPLAY',
      'DESCRIPTION:Напоминание: Скидка истекает завтра!',
      'TRIGGER:-P1D',
      'END:VALARM',
      // Alarm 2: 3 hours before
      'BEGIN:VALARM',
      'ACTION:DISPLAY',
      'DESCRIPTION:Внимание: Скидка истекает через 3 часа!',
      'TRIGGER:-PT3H',
      'END:VALARM',
      'END:VEVENT',
      'END:VCALENDAR',
    ].join('\r\n');
  }

  /**
   * Generate Google Calendar web/deep link URL
   */
  public static generateGoogleCalendarUrl(event: CalendarEventDetails): string {
    const formatGDate = (d: Date) =>
      d.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');

    const params = new URLSearchParams({
      action: 'TEMPLATE',
      text: event.title,
      dates: `${formatGDate(event.startDate)}/${formatGDate(event.endDate)}`,
      details: event.notes,
      location: event.location,
    });

    return `https://calendar.google.com/calendar/render?${params.toString()}`;
  }
}
