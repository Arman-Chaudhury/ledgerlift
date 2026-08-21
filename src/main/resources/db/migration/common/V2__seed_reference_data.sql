-- Reference data for the demo ledger. Real deployments replace this migration.
INSERT INTO currencies (code, name) VALUES ('USD', 'US Dollar'), ('EUR', 'Euro'), ('GBP', 'Pound Sterling');

INSERT INTO ledgers (id, name, currency_code) VALUES (1, 'US Primary', 'USD');

INSERT INTO accounts (ledger_id, code, name, account_type) VALUES
 (1, '1000', 'Cash - Operating',            'ASSET'),
 (1, '1100', 'Accounts Receivable',         'ASSET'),
 (1, '1200', 'Inventory',                   'ASSET'),
 (1, '1500', 'Fixed Assets',                'ASSET'),
 (1, '1510', 'Accumulated Depreciation',    'ASSET'),
 (1, '2000', 'Accounts Payable',            'LIABILITY'),
 (1, '2100', 'Accrued Liabilities',         'LIABILITY'),
 (1, '2500', 'Long-Term Debt',              'LIABILITY'),
 (1, '3000', 'Common Stock',                'EQUITY'),
 (1, '3100', 'Retained Earnings',           'EQUITY'),
 (1, '4000', 'Product Revenue',             'REVENUE'),
 (1, '4100', 'Service Revenue',             'REVENUE'),
 (1, '5000', 'Cost of Goods Sold',          'EXPENSE'),
 (1, '6000', 'Salaries Expense',            'EXPENSE'),
 (1, '6100', 'Rent Expense',                'EXPENSE'),
 (1, '6200', 'Depreciation Expense',        'EXPENSE'),
 (1, '6300', 'Utilities Expense',           'EXPENSE'),
 (1, '9999', 'Suspense (disabled)',         'EXPENSE');
UPDATE accounts SET enabled = FALSE WHERE code = '9999';

INSERT INTO periods (ledger_id, name, start_date, end_date, status) VALUES
 (1, 'Jan-26', DATE '2026-01-01', DATE '2026-01-31', 'CLOSED'),
 (1, 'Feb-26', DATE '2026-02-01', DATE '2026-02-28', 'CLOSED'),
 (1, 'Mar-26', DATE '2026-03-01', DATE '2026-03-31', 'OPEN'),
 (1, 'Apr-26', DATE '2026-04-01', DATE '2026-04-30', 'OPEN'),
 (1, 'May-26', DATE '2026-05-01', DATE '2026-05-31', 'OPEN'),
 (1, 'Jun-26', DATE '2026-06-01', DATE '2026-06-30', 'OPEN'),
 (1, 'Jul-26', DATE '2026-07-01', DATE '2026-07-31', 'OPEN'),
 (1, 'Aug-26', DATE '2026-08-01', DATE '2026-08-31', 'OPEN'),
 (1, 'Sep-26', DATE '2026-09-01', DATE '2026-09-30', 'OPEN'),
 (1, 'Oct-26', DATE '2026-10-01', DATE '2026-10-31', 'OPEN'),
 (1, 'Nov-26', DATE '2026-11-01', DATE '2026-11-30', 'OPEN'),
 (1, 'Dec-26', DATE '2026-12-01', DATE '2026-12-31', 'OPEN');
