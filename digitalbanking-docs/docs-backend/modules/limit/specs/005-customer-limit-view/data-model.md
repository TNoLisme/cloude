# Data model

Read `limit_config` LEFT JOIN `limit_usage` by customer, matching nullable account and business date. Select ACTIVE and effective window for customer plus optional selected account. A missing usage row means 0. Items preserve ID, account, amount, interval and version. Effective values are minima of the respective per-scope values; clamp remaining to zero. Data is a read snapshot, never a reservation.
