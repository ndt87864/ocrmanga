
import os
try:
    with open('text_remover.py', 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    print(f"Read {len(lines)} lines.")
    
    # We want to keep lines until the end of remove_text_with_mask, which is around 950.
    # We can detect the line "return f\"Error: {str(e)}\"" at line 950 (approx).
    
    cut_off_index = 952
    if len(lines) > cut_off_index:
        print(f"Truncating to {cut_off_index} lines.")
        with open('text_remover.py', 'w', encoding='utf-8') as f:
            f.writelines(lines[:cut_off_index])
        print("Write complete.")
    else:
        print("File not truncated.")
        
except Exception as e:
    print(f"Error: {e}")
