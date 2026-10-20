#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QObject>
#include <QSysInfo>

class DeviceManager : public QObject
{
    Q_OBJECT

public:
    explicit DeviceManager(QObject *parent = nullptr)
        : QObject(parent)
    {
    }

    Q_INVOKABLE QString deviceModel() const
    {
        return QSysInfo::prettyProductName();
    }

    Q_INVOKABLE QString operatingSystem() const
    {
        return QSysInfo::productType()
            + QStringLiteral(" ")
            + QSysInfo::productVersion();
    }

    Q_INVOKABLE QString architecture() const
    {
        return QSysInfo::currentCpuArchitecture();
    }

    Q_INVOKABLE QString bootloaderStatus() const
    {
        return QStringLiteral("Unknown — verification required");
    }

    Q_INVOKABLE QString safetyMessage() const
    {
        return QStringLiteral(
            "Unlocking may erase user data. "
            "Verify the exact device model, "
            "manufacturer policy, and backup "
            "before proceeding."
        );
    }
};

int main(int argc, char *argv[])
{
    QGuiApplication app(argc, argv);

    app.setApplicationName(QStringLiteral("BootloaderUnlocker"));
    app.setOrganizationName(QStringLiteral("BootloaderUnlocker"));

    DeviceManager deviceManager;

    QQmlApplicationEngine engine;

    engine.rootContext()->setContextProperty(
        QStringLiteral("deviceManager"),
        &deviceManager
    );

    const QUrl url(
        QStringLiteral("qrc:/qt/qml/BootloaderUnlocker/Main.qml")
    );

    QObject::connect(
        &engine,
        &QQmlApplicationEngine::objectCreationFailed,
        &app,
        []() {
            QCoreApplication::exit(-1);
        },
        Qt::QueuedConnection
    );

    engine.load(url);

    return app.exec();
}

#include "main.moc"
