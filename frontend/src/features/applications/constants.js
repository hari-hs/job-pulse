// Mirrors backend/src/main/java/com/jobpulse/application/ApplicationStatus.java.
// No metadata endpoint exposes this yet, so it's duplicated here for now.
export const APPLICATION_STATUSES = [
  'APPLIED',
  'ONLINE_ASSESSMENT',
  'PHONE_SCREEN',
  'ONSITE',
  'OFFER',
  'REJECTED',
  'WITHDRAWN',
]

export function formatStatus(status) {
  return status.replaceAll('_', ' ')
}
