-- Canonical pending-student fields. This migration changes no existing rows and
-- creates no client grants or RLS policies. Flask's DATABASE_URL role remains
-- the only application database caller.
ALTER TABLE public.student_master_records
    ADD COLUMN IF NOT EXISTS phone TEXT;

ALTER TABLE public.student_master_records
    ADD COLUMN IF NOT EXISTS notes TEXT NOT NULL DEFAULT '';

-- Preserve contact data already linked to activated records. Pending records
-- are intentionally left untouched until an administrator resumes them through
-- the transactional Flask endpoint.
UPDATE public.student_master_records sm
SET phone = COALESCE(NULLIF(sm.phone, ''), NULLIF(u.phone, ''), NULLIF(sp.phone, ''))
FROM public.users u
LEFT JOIN public.student_profiles sp ON sp.user_id = u.id
WHERE sm.login_user_id = u.id
  AND (sm.phone IS NULL OR BTRIM(sm.phone) = '');
