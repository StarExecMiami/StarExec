import re

def analyze_sql_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    # Split into functions (rough split by CREATE OR REPLACE FUNCTION)
    # This regex looks for the start of a function and captures until the next one or end of file
    # It's not perfect but good enough for a heuristic scan
    function_blocks = re.split(r'(?=CREATE OR REPLACE FUNCTION)', content)

    for block in function_blocks:
        if 'RETURNS TABLE' not in block:
            continue

        # Extract function name
        name_match = re.search(r'FUNCTION\s+([^\s\(]+)', block, re.IGNORECASE)
        func_name = name_match.group(1) if name_match else "Unknown"

        # Extract RETURNS TABLE columns
        # RETURNS TABLE(col1 type, col2 type, ...)
        returns_match = re.search(r'RETURNS\s+TABLE\s*\((.*?)\)\s+AS\s+\$\$', block, re.IGNORECASE | re.DOTALL)
        if not returns_match:
            continue

        columns_str = returns_match.group(1)
        # Split by comma, but be careful of types like NUMERIC(10,2) - though usually simple types here
        # Simple split by comma should work for most cases in this file
        columns_raw = columns_str.split(',')
        return_cols = []
        for col in columns_raw:
            col = col.strip()
            # Get the first word which is the column name
            col_name = col.split()[0]
            return_cols.append(col_name)

        # Extract the body (between $$ and $$)
        body_match = re.search(r'\$\$(.*?)\$\$', block, re.DOTALL)
        if not body_match:
            continue
        body = body_match.group(1)

        # Check for usage of return columns in SELECT statements
        # We look for " col_name " or ", col_name " or " col_name,"
        # We want to avoid "table.col_name"
        
        potential_ambiguities = []
        for col in return_cols:
            # Regex to find the column name NOT preceded by a dot
            # We look for word boundaries
            # (?<!\.) means "not preceded by a dot"
            pattern = r'(?<!\.)\b' + re.escape(col) + r'\b'
            
            # We only care if it's in the SELECT list or WHERE clause, but simply existing in the body 
            # without qualification is a strong hint if it matches a table column.
            # However, we don't know table columns here.
            # But if the function returns 'id' and does 'SELECT id FROM table', it's ambiguous if table has 'id'.
            # Most tables have 'id'.
            
            matches = re.findall(pattern, body)
            # We expect at least one match if it's used in the return query (e.g. SELECT col AS col)
            # But wait, "SELECT table.col" matches "col" in the return list? No, we check the body.
            # If the body has "SELECT table.col", the regex (?<!\.) won't match.
            # If the body has "SELECT col", it will match.
            
            if matches:
                # If we find an unqualified reference, it's a candidate.
                # But wait, "SELECT 1 AS col" is fine.
                # "SELECT col FROM table" is the problem.
                # It's hard to distinguish "AS col" from "col" without a parser.
                # But let's list them and I will manually check.
                potential_ambiguities.append(col)

        if potential_ambiguities:
            print(f"Function: {func_name}")
            print(f"  Potential ambiguous return columns: {', '.join(potential_ambiguities)}")
            print("-" * 40)

if __name__ == "__main__":
    analyze_sql_file("src/main/resources/db/migration/R__procedures_and_views.sql")
