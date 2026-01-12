
import os

filename = 'text_remover.py'
with open(filename, 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Logic:
# Keep everything up to line 950.
# The duplication starts at line 951 with indentation errors.

if len(lines) > 951:
    print(f"Truncating file from {len(lines)} to 951 lines.")
    with open(filename, 'w', encoding='utf-8') as f:
        f.writelines(lines[:951])
    print("Done.")
else:
    print("File is already short enough.")
