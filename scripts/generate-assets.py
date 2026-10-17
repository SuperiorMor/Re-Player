import os
import zlib
import struct

def create_png(width, height, get_pixel_func):
    """Generate a raw PNG without external libraries using pure Python + zlib"""
    raw_data = bytearray()
    for y in range(height):
        raw_data.append(0)  # Filter type 0 (None)
        for x in range(width):
            r, g, b, a = get_pixel_func(x, y, width, height)
            raw_data.extend([r, g, b, a])
    
    compressed = zlib.compress(raw_data)
    
    png = bytearray(b'\x89PNG\r\n\x1a\n')
    # IHDR chunk
    ihdr = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    png.extend(struct.pack('>I', len(ihdr)))
    png.extend(b'IHDR')
    png.extend(ihdr)
    png.extend(struct.pack('>I', zlib.crc32(b'IHDR' + ihdr)))
    
    # IDAT chunk
    png.extend(struct.pack('>I', len(compressed)))
    png.extend(b'IDAT')
    png.extend(compressed)
    png.extend(struct.pack('>I', zlib.crc32(b'IDAT' + compressed)))
    
    # IEND chunk
    png.extend(struct.pack('>I', 0))
    png.extend(b'IEND')
    png.extend(struct.pack('>I', zlib.crc32(b'IEND')))
    
    return bytes(png)

def icon_shader(x, y, w, h):
    # Normalized coords
    nx = x / w
    ny = y / h
    
    # Background: Indigo #6366F1
    bg_r, bg_g, bg_b = 99, 102, 241
    
    # Inner Ticket / Blotter Stamp Box
    margin = 0.2
    if margin <= nx <= (1 - margin) and margin <= ny <= (1 - margin):
        # White ticket card
        # Ticket side cutouts
        cx_left = margin
        cx_right = 1 - margin
        cy = 0.5
        dist_left = ((nx - cx_left)**2 + (ny - cy)**2)**0.5
        dist_right = ((nx - cx_right)**2 + (ny - cy)**2)**0.5
        
        if dist_left < 0.08 or dist_right < 0.08:
            return (bg_r, bg_g, bg_b, 255)
        
        # Blotter Ink Dot (Top Right)
        dot_dist = ((nx - 0.72)**2 + (ny - 0.28)**2)**0.5
        if dot_dist < 0.06:
            return (255, 87, 51, 255) # Coral #FF5733
        
        # Percent symbol / Promo mark in center
        center_dist = ((nx - 0.5)**2 + (ny - 0.5)**2)**0.5
        if center_dist < 0.12:
            return (79, 70, 229, 255) # Deep Violet
        
        return (255, 255, 255, 255)
        
    return (bg_r, bg_g, bg_b, 255)

os.makedirs('assets', exist_ok=True)

# Generate icon.png (256x256)
with open('assets/icon.png', 'wb') as f:
    f.write(create_png(256, 256, icon_shader))

# Generate adaptive-icon.png (256x256)
with open('assets/adaptive-icon.png', 'wb') as f:
    f.write(create_png(256, 256, icon_shader))

# Generate favicon.png (48x48)
with open('assets/favicon.png', 'wb') as f:
    f.write(create_png(48, 48, icon_shader))

# Generate splash.png (400x400)
with open('assets/splash.png', 'wb') as f:
    f.write(create_png(400, 400, icon_shader))

print("Assets generated successfully!")
