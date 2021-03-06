package games.strategy.engine.data;

import static com.google.common.base.Preconditions.checkNotNull;

/** A game data change that captures a change to an attachment property value. */
public class ChangeAttachmentChange extends Change {
  private static final long serialVersionUID = -6447264150952218283L;
  private final Attachable attachedTo;
  private final String attachmentName;
  private final Object newValue;
  private final Object oldValue;
  private final String property;
  private boolean clearFirst = false;

  /**
   * Initializes a new instance of the ChangeAttachmentChange class.
   *
   * @param attachment The attachment to be updated.
   * @param newValue The new value for the property.
   * @param property The property name.
   */
  public ChangeAttachmentChange(
      final IAttachment attachment, final Object newValue, final String property) {
    checkNotNull(attachment, "null attachment; newValue: " + newValue + ", property: " + property);

    attachedTo = attachment.getAttachedTo();
    attachmentName = attachment.getName();
    oldValue = attachment.getPropertyOrThrow(property).getValue();
    this.newValue = newValue;
    this.property = property;
  }

  /**
   * You don't want to clear the variable first unless you are setting some variable where the
   * setting method is actually adding things to a list rather than overwriting.
   */
  public ChangeAttachmentChange(
      final IAttachment attachment,
      final Object newValue,
      final String property,
      final boolean resetFirst) {
    checkNotNull(attachment, "null attachment; newValue: " + newValue + ", property: " + property);

    attachedTo = attachment.getAttachedTo();
    clearFirst = resetFirst;
    attachmentName = attachment.getName();
    oldValue = attachment.getPropertyOrThrow(property).getValue();
    this.newValue = newValue;
    this.property = property;
  }

  /**
   * You don't want to clear the variable first unless you are setting some variable where the
   * setting method is actually adding things to a list rather than overwriting.
   */
  public ChangeAttachmentChange(
      final Attachable attachTo,
      final String attachmentName,
      final Object newValue,
      final Object oldValue,
      final String property,
      final boolean resetFirst) {
    this.attachmentName = attachmentName;
    attachedTo = attachTo;
    this.newValue = newValue;
    this.oldValue = oldValue;
    this.property = property;
    clearFirst = resetFirst;
  }

  public Attachable getAttachedTo() {
    return attachedTo;
  }

  public String getAttachmentName() {
    return attachmentName;
  }

  @Override
  public void perform(final GameData data) {
    final IAttachment attachment = attachedTo.getAttachment(attachmentName);
    final MutableProperty<?> attachmentProperty = attachment.getPropertyOrThrow(property);
    if (clearFirst) {
      attachmentProperty.resetValue();
    }
    try {
      attachmentProperty.setValue(newValue);
    } catch (final MutableProperty.InvalidValueException e) {
      throw new IllegalStateException(
          String.format(
              "failed to set value '%s' on property '%s' for attachment '%s' associated with '%s'",
              newValue, property, attachmentName, attachedTo),
          e);
    }
  }

  @Override
  public Change invert() {
    return new ChangeAttachmentChange(
        attachedTo, attachmentName, oldValue, newValue, property, clearFirst);
  }

  @Override
  public String toString() {
    return "ChangAttachmentChange attached to:"
        + attachedTo
        + " name:"
        + attachmentName
        + " new value:"
        + newValue
        + " old value:"
        + oldValue;
  }
}
