ALTER TABLE recommendation_events
  DROP CONSTRAINT chk_recommendation_events_type;

ALTER TABLE recommendation_events
  ADD CONSTRAINT chk_recommendation_events_type CHECK (
    event_type IN (
      'IMPRESSION',
      'CLICK',
      'DETAIL_VIEW',
      'SAVE',
      'CHANGE_ADOPTED',
      'ADD_BUILD_TO_DRAFT',
      'ADD_PART_TO_DRAFT',
      'ORDER_INTENT',
      'REJECT',
      'CHANGE_REVERTED',
      'AS_CONFIRMED_NEGATIVE',
      'ADMIN_PROMOTE',
      'ADMIN_DEMOTE'
    )
  );
